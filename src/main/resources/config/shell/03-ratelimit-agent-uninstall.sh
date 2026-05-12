#!/usr/bin/env bash
# @title: 卸载限速本地代理
# @description: 停止并移除 airopscat-ratelimit systemd 服务，清理限速代理写入的 tc、iptables 和 nftables 规则

set -euo pipefail

SERVICE_NAME="airopscat-ratelimit.service"
AGENT_PATH="/usr/local/bin/airopscat-ratelimit-agent"
SYSTEMD_UNIT_PATH="/etc/systemd/system/${SERVICE_NAME}"
DEFAULT_PATH="/etc/default/airopscat-ratelimit"
CONFIG_DIR="/etc/airopscat/ratelimit"
IPTS_CHAIN="AIROPSCAT_MARK"
NFT_TABLE="airopscat_ratelimit"
PURGE_CONFIG=0

log() {
  printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    log "错误: 请使用 root 用户执行该脚本"
    exit 1
  fi
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --purge)
        PURGE_CONFIG=1
        ;;
      -h|--help)
        cat <<'EOF'
用法: ./03-ratelimit-agent-uninstall.sh [--purge]

默认仅卸载服务、程序和运行时限速规则，保留 /etc/airopscat/ratelimit/accounts.json。
传入 --purge 时会同时删除 /etc/airopscat/ratelimit 配置目录。
EOF
        exit 0
        ;;
      *)
        log "错误: 不支持的参数 $1"
        exit 1
        ;;
    esac
    shift
  done
}

stop_service() {
  if command -v systemctl >/dev/null 2>&1; then
    log "停止并禁用 ${SERVICE_NAME}"
    systemctl stop "${SERVICE_NAME}" 2>/dev/null || true
    systemctl disable "${SERVICE_NAME}" 2>/dev/null || true
  else
    log "警告: 未找到 systemctl，跳过服务停止"
  fi
}

detect_nic() {
  local nic=""

  if [[ -f "${DEFAULT_PATH}" ]]; then
    # shellcheck disable=SC1090
    source "${DEFAULT_PATH}" || true
    nic="${AIROPSCAT_RATELIMIT_NIC:-}"
  fi

  if [[ -z "${nic}" ]] && command -v ip >/dev/null 2>&1; then
    nic="$(ip route show default 2>/dev/null | awk '
      {
        for (i = 1; i <= NF; i++) {
          if ($i == "dev" && (i + 1) <= NF) {
            print $(i + 1);
            exit;
          }
        }
      }')"
  fi

  if [[ -z "${nic}" ]]; then
    nic="eth0"
  fi

  printf '%s\n' "${nic}"
}

cleanup_tc() {
  if ! command -v tc >/dev/null 2>&1; then
    log "警告: 未找到 tc，跳过 tc 规则清理"
    return 0
  fi

  local nic
  nic="$(detect_nic)"
  log "清理 ${nic} 上的 HTB 限速规则"
  tc qdisc del dev "${nic}" root 2>/dev/null || true
}

cleanup_iptables() {
  if ! command -v iptables >/dev/null 2>&1; then
    log "警告: 未找到 iptables，跳过 mangle 表清理"
    return 0
  fi

  log "清理 iptables mangle 表中的限速代理规则"
  while iptables -t mangle -C OUTPUT -j "${IPTS_CHAIN}" 2>/dev/null; do
    iptables -t mangle -D OUTPUT -j "${IPTS_CHAIN}" 2>/dev/null || true
  done

  while iptables -t mangle -C OUTPUT -j CONNMARK --restore-mark 2>/dev/null; do
    iptables -t mangle -D OUTPUT -j CONNMARK --restore-mark 2>/dev/null || true
  done

  while iptables -t mangle -C PREROUTING -j CONNMARK --restore-mark 2>/dev/null; do
    iptables -t mangle -D PREROUTING -j CONNMARK --restore-mark 2>/dev/null || true
  done

  iptables -t mangle -F "${IPTS_CHAIN}" 2>/dev/null || true
  iptables -t mangle -X "${IPTS_CHAIN}" 2>/dev/null || true
}

cleanup_nftables() {
  if ! command -v nft >/dev/null 2>&1; then
    log "警告: 未找到 nft，跳过 nftables 规则清理"
    return 0
  fi

  log "清理 nftables 限速规则"
  nft delete table inet "${NFT_TABLE}" 2>/dev/null || true
}

remove_files() {
  log "删除限速代理程序和 systemd 配置"
  rm -f "${AGENT_PATH}" "${SYSTEMD_UNIT_PATH}" "${DEFAULT_PATH}"

  if ((PURGE_CONFIG == 1)); then
    log "删除限速代理配置目录 ${CONFIG_DIR}"
    rm -rf "${CONFIG_DIR}"
  else
    log "保留限速账号配置目录 ${CONFIG_DIR}；如需删除请使用 --purge"
  fi
}

reload_systemd() {
  if command -v systemctl >/dev/null 2>&1; then
    log "刷新 systemd 配置"
    systemctl daemon-reload
    systemctl reset-failed "${SERVICE_NAME}" 2>/dev/null || true
  fi
}

main() {
  parse_args "$@"
  require_root
  stop_service
  cleanup_tc
  cleanup_iptables
  cleanup_nftables
  remove_files
  reload_systemd
  log "限速本地代理卸载完成"
}

main "$@"
