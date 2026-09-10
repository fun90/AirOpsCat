#!/usr/bin/env bash
# @title: 安装账户防共享 guard agent
# @description: 部署 Python 常驻 agent，复用 HTTP/HTTPS 长连接读取本机 Clash API 并通过 guard-sync 上报账户连接/IP，原子写入跨节点配额文件

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
GUARD_INCLUDE_CONNECTION_REFS="${guard_include_connection_refs:-false}"
SINGBOX_CONFIG="${singbox_config:-/etc/sing-box/config.json}"
QUOTA_DIR="/run/airopscat"
QUOTA_FILE="${QUOTA_DIR}/account-quota.json"
AGENT_BIN="/usr/local/bin/airopscat-guard-agent.py"
AGENT_SERVICE="/etc/systemd/system/airopscat-guard-agent.service"

install_dependencies() {
  # Python 标准库同时负责 JSON 聚合、gzip 压缩和 HTTP 长连接。
  local missing=()
  command -v python3 >/dev/null 2>&1 || missing+=(python3)
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
#!/usr/bin/env python3
# AirOpsCat 账户防共享 guard agent（由 08-guard-agent.sh 安装，勿手工编辑）
#
# 每个周期：
#   1. 读本机 sing-box 配置解析 Clash API 端口与 secret
#   2. GET /connections，按 authUser 聚合连接数与去重 sourceIP，同时生成在线 IP 聚合记录
#   3. POST guard-sync（带本节点数据与在线 IP 聚合记录），取回全局配额黑名单
#   4. 原子写入 /run/airopscat/account-quota.json 供内核读取
#
# 失败（Clash API 不可达、中心无响应、解析异常）时不覆盖旧文件，
# 由内核侧 TTL 过期后 fail-open，避免误断网。

import gzip
import http.client
import json
import os
import ssl
import sys
import tempfile
import time
from urllib.parse import urlsplit


SERVER_IP = os.environ["SERVER_IP"]
GUARD_SYNC_URL = os.environ["GUARD_SYNC_URL"]
AIROPSCAT_API_TOKEN = os.environ.get("AIROPSCAT_API_TOKEN", "")
SYNC_INTERVAL_SECONDS = max(1, int(os.environ.get("SYNC_INTERVAL_SECONDS", "10")))
GUARD_INCLUDE_CONNECTION_REFS = os.environ.get("GUARD_INCLUDE_CONNECTION_REFS", "false").lower() in {
    "1", "true", "yes", "on"
}
SINGBOX_CONFIG = os.environ.get("SINGBOX_CONFIG", "/etc/sing-box/config.json")
QUOTA_DIR = os.environ.get("QUOTA_DIR", "/run/airopscat")
QUOTA_FILE = os.environ.get("QUOTA_FILE", os.path.join(QUOTA_DIR, "account-quota.json"))

RETRYABLE_ERRORS = (
    http.client.HTTPException,
    http.client.CannotSendRequest,
    http.client.RemoteDisconnected,
    BrokenPipeError,
    ConnectionResetError,
    TimeoutError,
    ssl.SSLError,
    OSError,
)
SYNC_ERRORS = RETRYABLE_ERRORS + (RuntimeError, TypeError, ValueError, AttributeError)


def log(message):
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{timestamp}] guard-agent: {message}", file=sys.stderr, flush=True)


class PersistentHttpClient:
    def __init__(self, base_url, timeout):
        parsed = urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError(f"无效的 HTTP 地址: {base_url}")
        self.scheme = parsed.scheme
        self.host = parsed.hostname
        self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self.base_path = parsed.path.rstrip("/")
        self.timeout = timeout
        self.connection = None

    def close(self):
        if self.connection is not None:
            try:
                self.connection.close()
            except OSError:
                pass
            finally:
                self.connection = None

    def _connect(self):
        if self.scheme == "https":
            return http.client.HTTPSConnection(
                self.host,
                self.port,
                timeout=self.timeout,
                context=ssl.create_default_context(),
            )
        return http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)

    def request(self, method, path="", body=None, headers=None):
        target = f"{self.base_path}{path}" or "/"
        for attempt in range(2):
            try:
                if self.connection is None:
                    self.connection = self._connect()
                self.connection.request(method, target, body=body, headers=headers or {})
                response = self.connection.getresponse()
                payload = response.read()
                status = response.status
                if response.will_close:
                    self.close()
                return status, payload
            except RETRYABLE_ERRORS:
                self.close()
                if attempt == 1:
                    raise
        raise RuntimeError("HTTP 请求重试失败")


def resolve_clash_api():
    controller = "127.0.0.1:19191"
    secret = ""
    try:
        with open(SINGBOX_CONFIG, "r", encoding="utf-8") as config_file:
            config = json.load(config_file)
        clash_api = config.get("experimental", {}).get("clash_api", {})
        controller = clash_api.get("external_controller") or controller
        secret = clash_api.get("secret") or ""
    except FileNotFoundError:
        pass
    except (OSError, TypeError, ValueError) as error:
        log(f"读取 sing-box 配置失败，使用默认 Clash API: {error}")
    if not controller.startswith(("http://", "https://")):
        controller = f"http://{controller}"
    return controller, secret


def node_tag(metadata):
    connection_type = metadata.get("type") or ""
    return connection_type.rsplit("/", 1)[-1] if "/" in connection_type else ""


def build_guard_request(snapshot):
    accounts = {}
    online_groups = {}
    connections = snapshot.get("connections") or []
    for connection in connections:
        metadata = connection.get("metadata") or {}
        account_no = metadata.get("authUser")
        if account_no is None or account_no == "":
            continue

        source_ip = metadata.get("sourceIP") or ""
        account = accounts.setdefault(account_no, {"connections": 0, "ips": set()})
        account["connections"] += 1
        if source_ip:
            account["ips"].add(source_ip)

            key = (account_no, node_tag(metadata))
            online = online_groups.setdefault(key, {"clientIps": set(), "connections": []})
            online["clientIps"].add(source_ip)
            if GUARD_INCLUDE_CONNECTION_REFS:
                online["connections"].append({
                    "clientIp": source_ip,
                    "connectionId": connection.get("id") or "",
                    "start": connection.get("start") or "",
                })

    account_reports = [
        {
            "accountNo": account_no,
            "connections": accounts[account_no]["connections"],
            "ips": sorted(accounts[account_no]["ips"]),
        }
        for account_no in sorted(accounts)
    ]

    online_reports = []
    for account_no, tag in sorted(online_groups):
        group = online_groups[(account_no, tag)]
        report = {
            "accountNo": account_no,
            "nodeTag": tag,
            "clientIps": sorted(group["clientIps"]),
        }
        if GUARD_INCLUDE_CONNECTION_REFS:
            report["connections"] = group["connections"]
        online_reports.append(report)

    return {
        "nodeIp": SERVER_IP,
        "generatedAtEpochSeconds": int(time.time()),
        "accounts": account_reports,
        "onlineAccountIps": online_reports,
    }


def write_quota_file(payload):
    os.makedirs(QUOTA_DIR, exist_ok=True)
    descriptor, temp_path = tempfile.mkstemp(prefix=".account-quota.", dir=QUOTA_DIR)
    try:
        with os.fdopen(descriptor, "wb") as temp_file:
            temp_file.write(payload)
        os.chmod(temp_path, 0o644)
        os.replace(temp_path, QUOTA_FILE)
    finally:
        if os.path.exists(temp_path):
            os.unlink(temp_path)


def main():
    os.makedirs(QUOTA_DIR, exist_ok=True)
    guard_client = PersistentHttpClient(GUARD_SYNC_URL, timeout=5)
    clash_client = None
    clash_url = None
    clash_secret = None

    log(f"启动：nodeIp={SERVER_IP}, interval={SYNC_INTERVAL_SECONDS}s, HTTPS 长连接已启用")
    while True:
        try:
            resolved_url, resolved_secret = resolve_clash_api()
            if resolved_url != clash_url or resolved_secret != clash_secret:
                if clash_client is not None:
                    clash_client.close()
                clash_url = resolved_url
                clash_secret = resolved_secret
                clash_client = PersistentHttpClient(clash_url, timeout=3)
                log(f"Clash API 已更新: {clash_url}")

            clash_headers = {}
            if clash_secret:
                clash_headers["Authorization"] = f"Bearer {clash_secret}"
            status, connection_payload = clash_client.request("GET", "/connections", headers=clash_headers)
            if status < 200 or status >= 300:
                raise RuntimeError(f"Clash API 返回 HTTP {status}")
            snapshot = json.loads(connection_payload)

            request_body = json.dumps(
                build_guard_request(snapshot), ensure_ascii=False, separators=(",", ":")
            ).encode("utf-8")
            compressed_body = gzip.compress(request_body)
            guard_headers = {
                "Content-Type": "application/json",
                "Content-Encoding": "gzip",
            }
            if AIROPSCAT_API_TOKEN:
                guard_headers["Token"] = AIROPSCAT_API_TOKEN

            status, response_payload = guard_client.request(
                "POST", body=compressed_body, headers=guard_headers
            )
            if status < 200 or status >= 300:
                raise RuntimeError(f"guard-sync 返回 HTTP {status}")
            response = json.loads(response_payload)
            if not response.get("schemaVersion"):
                raise ValueError("guard-sync 响应缺少 schemaVersion")
            write_quota_file(response_payload)
        except SYNC_ERRORS as error:
            log(f"同步失败，跳过本轮（保留旧配额文件）: {error}")
        time.sleep(SYNC_INTERVAL_SECONDS)


if __name__ == "__main__":
    main()
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
Environment=GUARD_INCLUDE_CONNECTION_REFS=${GUARD_INCLUDE_CONNECTION_REFS}
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
