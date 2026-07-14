#!/usr/bin/env bash
# @title: 安装账户防共享 guard agent
# @description: 部署常驻 agent，周期读取本机 Clash API 统计各账户连接/IP，通过 guard-sync 上报并取回跨节点配额，原子写入 /run/airopscat/account-quota.json 供 sing-box 内核读取

set -euo pipefail

log() {
  printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    log "错误: 请使用 root 用户执行该脚本"
    exit 1
  fi
}

# 由下发框架注入的变量（buildScriptExecutionCommand）：
#   server_ip            本节点 IP，作为上报的 nodeIp
#   airopscat_domain     中心域名，用于拼接 guard-sync URL
#   airopscat_api_token  中心 API Token，作为 guard-sync 的 Token 请求头
SERVER_IP="${server_ip:-}"
AIROPSCAT_DOMAIN="${airopscat_domain:-}"
AIROPSCAT_API_TOKEN="${airopscat_api_token:-}"

# 可调参数（如需自定义可在此覆盖）
SYNC_INTERVAL_SECONDS="${guard_sync_interval:-10}"
SINGBOX_CONFIG="${singbox_config:-/etc/sing-box/config.json}"
QUOTA_DIR="/run/airopscat"
QUOTA_FILE="${QUOTA_DIR}/account-quota.json"
AGENT_BIN="/usr/local/bin/airopscat-guard-agent.sh"
AGENT_SERVICE="/etc/systemd/system/airopscat-guard-agent.service"

install_dependencies() {
  # agent 依赖 jq 解析 Clash API JSON、curl 发起 HTTP
  local missing=()
  command -v jq >/dev/null 2>&1 || missing+=(jq)
  command -v curl >/dev/null 2>&1 || missing+=(curl)
  if [[ ${#missing[@]} -eq 0 ]]; then
    return
  fi
  log "安装依赖: ${missing[*]}"
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -y && apt-get install -y "${missing[@]}"
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y "${missing[@]}"
  elif command -v yum >/dev/null 2>&1; then
    yum install -y "${missing[@]}"
  else
    log "错误: 未找到 apt/dnf/yum，请手动安装: ${missing[*]}"
    exit 1
  fi
}

validate_env() {
  if [[ -z "${SERVER_IP}" ]]; then
    log "错误: 缺少 server_ip，无法确定上报的 nodeIp"
    exit 1
  fi
  if [[ -z "${AIROPSCAT_DOMAIN}" ]]; then
    log "错误: 缺少 airopscat_domain，无法拼接 guard-sync 地址"
    exit 1
  fi
}

write_agent_script() {
  log "写入 agent 脚本: ${AGENT_BIN}"
  # 用带引号的 heredoc，令 agent 脚本体保持字面量；运行期变量由 systemd 环境注入
  cat > "${AGENT_BIN}" <<'AGENT_EOF'
#!/usr/bin/env bash
# AirOpsCat 账户防共享 guard agent（由 02-guard-agent.sh 安装，勿手工编辑）
#
# 每个周期：
#   1. 读本机 sing-box 配置解析 Clash API 端口与 secret
#   2. GET /connections，按 authUser 聚合连接数与去重 sourceIP，同时生成在线连接明细
#   3. POST guard-sync（带本节点数据与在线明细），取回全局配额黑名单
#   4. 原子写入 /run/airopscat/account-quota.json 供内核读取
#
# 失败（Clash API 不可达、中心无响应、解析异常）时不覆盖旧文件，
# 由内核侧 TTL 过期后 fail-open，避免误断网。

set -uo pipefail

log() { printf '[%s] guard-agent: %s\n' "$(date '+%F %T')" "$*" >&2; }

SERVER_IP="${SERVER_IP:?}"
GUARD_SYNC_URL="${GUARD_SYNC_URL:?}"
AIROPSCAT_API_TOKEN="${AIROPSCAT_API_TOKEN:-}"
SYNC_INTERVAL_SECONDS="${SYNC_INTERVAL_SECONDS:-10}"
SINGBOX_CONFIG="${SINGBOX_CONFIG:-/etc/sing-box/config.json}"
QUOTA_DIR="${QUOTA_DIR:-/run/airopscat}"
QUOTA_FILE="${QUOTA_FILE:-${QUOTA_DIR}/account-quota.json}"

# 从本机 sing-box 配置解析 Clash API 监听地址与凭证（单一事实来源）
resolve_clash_api() {
  local controller secret
  if [[ -f "${SINGBOX_CONFIG}" ]]; then
    controller="$(jq -r '.experimental.clash_api.external_controller // empty' "${SINGBOX_CONFIG}" 2>/dev/null || true)"
    secret="$(jq -r '.experimental.clash_api.secret // empty' "${SINGBOX_CONFIG}" 2>/dev/null || true)"
  fi
  CLASH_API_ADDR="${controller:-127.0.0.1:19191}"
  CLASH_API_SECRET="${secret:-}"
}

sync_once() {
  local conn_json auth_header=()
  if [[ -n "${CLASH_API_SECRET}" ]]; then
    auth_header=(-H "Authorization: Bearer ${CLASH_API_SECRET}")
  fi

  conn_json="$(curl -fsS --max-time 3 "${auth_header[@]}" "http://${CLASH_API_ADDR}/connections" 2>/dev/null || true)"
  if [[ -z "${conn_json}" ]]; then
    log "读取 Clash API 失败，跳过本轮（保留旧配额文件）"
    return
  fi

  # 将 /connections 转换为 guard-sync 请求体：
  #   accounts：按 metadata.authUser 分组 → connections 计数 + sourceIP 去重
  #   onlineConnections：当前在线连接明细，供中心刷新 account_online_ip
  #   过滤掉 authUser 或 sourceIP 为空的在线明细（落地节点公共账号等）
  local body
  body="$(printf '%s' "${conn_json}" | jq -c \
    --arg nodeIp "${SERVER_IP}" \
    --argjson now "$(date +%s)" '
    def node_tag:
      (.metadata.type // "")
      | if contains("/") then split("/")[-1] else "" end;
    def valid_user:
      .metadata.authUser != null and .metadata.authUser != "";
    def valid_source:
      .metadata.sourceIP != null and .metadata.sourceIP != "";
    def online_record:
      {
        accountNo: .metadata.authUser,
        clientIp: .metadata.sourceIP,
        connectionId: (.id // ""),
        nodeTag: node_tag,
        start: (.start // "")
      };
    [ .connections[]? | select(valid_user) ] as $validConnections |
    {
      nodeIp: $nodeIp,
      generatedAtEpochSeconds: $now,
      accounts: (
        [ $validConnections[]
          | { authUser: .metadata.authUser, sourceIP: (.metadata.sourceIP // "") }
        ]
        | group_by(.authUser)
        | map({
            accountNo: .[0].authUser,
            connections: length,
            ips: ( [ .[].sourceIP | select(. != "") ] | unique )
          })
      ),
      onlineConnections: (
        [ $validConnections[]
          | select(valid_source)
          | online_record
        ]
      )
    }' 2>/dev/null || true)"

  if [[ -z "${body}" ]]; then
    log "生成 guard-sync 请求体失败，跳过本轮"
    return
  fi

  local resp token_header=()
  if [[ -n "${AIROPSCAT_API_TOKEN}" ]]; then
    token_header=(-H "Token: ${AIROPSCAT_API_TOKEN}")
  fi

  resp="$(curl -fsS --max-time 5 -X POST \
    -H "Content-Type: application/json" \
    "${token_header[@]}" \
    --data "${body}" \
    "${GUARD_SYNC_URL}" 2>/dev/null || true)"
  if [[ -z "${resp}" ]]; then
    log "guard-sync 无响应，跳过本轮（保留旧配额文件）"
    return
  fi

  # 校验响应是合法 JSON 且含 schemaVersion，避免把错误页写入配额文件
  if ! printf '%s' "${resp}" | jq -e '.schemaVersion' >/dev/null 2>&1; then
    log "guard-sync 响应非预期 JSON，跳过本轮"
    return
  fi

  # 原子写入：临时文件 + mv，避免内核读到半写状态
  local tmp
  tmp="$(mktemp "${QUOTA_DIR}/.account-quota.XXXXXX")" || return
  printf '%s' "${resp}" > "${tmp}"
  chmod 0644 "${tmp}"
  mv -f "${tmp}" "${QUOTA_FILE}"
}

main() {
  mkdir -p "${QUOTA_DIR}"
  resolve_clash_api
  log "启动：nodeIp=${SERVER_IP}, clashApi=${CLASH_API_ADDR}, interval=${SYNC_INTERVAL_SECONDS}s"
  while true; do
    # 每轮重新解析，容忍 sing-box 配置在运行期变更
    resolve_clash_api
    sync_once
    sleep "${SYNC_INTERVAL_SECONDS}"
  done
}

main "$@"
AGENT_EOF
  chmod 0755 "${AGENT_BIN}"
}

write_systemd_unit() {
  log "写入 systemd 服务: ${AGENT_SERVICE}"
  # guard-sync 地址在安装期固化到服务环境；agent 脚本体保持与部署无关
  local guard_sync_url="https://${AIROPSCAT_DOMAIN}/api/open/guard-sync"
  cat > "${AGENT_SERVICE}" <<UNIT_EOF
[Unit]
Description=AirOpsCat 账户防共享 guard agent
After=network-online.target sing-box.service
Wants=network-online.target

[Service]
Type=simple
Environment=SERVER_IP=${SERVER_IP}
Environment=GUARD_SYNC_URL=${guard_sync_url}
Environment=AIROPSCAT_API_TOKEN=${AIROPSCAT_API_TOKEN}
Environment=SYNC_INTERVAL_SECONDS=${SYNC_INTERVAL_SECONDS}
Environment=SINGBOX_CONFIG=${SINGBOX_CONFIG}
Environment=QUOTA_DIR=${QUOTA_DIR}
Environment=QUOTA_FILE=${QUOTA_FILE}
ExecStart=${AGENT_BIN}
Restart=always
RestartSec=3
# 配额文件含账号与客户端 IP，限制读取权限
UMask=0022

[Install]
WantedBy=multi-user.target
UNIT_EOF
}

enable_service() {
  log "重载 systemd 并启动服务"
  systemctl daemon-reload
  systemctl enable airopscat-guard-agent.service >/dev/null 2>&1 || true
  systemctl restart airopscat-guard-agent.service
  sleep 1
  if systemctl is-active --quiet airopscat-guard-agent.service; then
    log "guard agent 已启动"
  else
    log "警告: guard agent 未处于 active 状态，请检查 journalctl -u airopscat-guard-agent"
  fi
}

main() {
  require_root
  validate_env
  install_dependencies
  mkdir -p "${QUOTA_DIR}"
  write_agent_script
  write_systemd_unit
  enable_service
  log "账户防共享 guard agent 安装完成"
  log "配额文件: ${QUOTA_FILE}"
  log "查看日志: journalctl -u airopscat-guard-agent -f"
}

main "$@"
