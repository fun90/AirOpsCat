#!/usr/bin/env bash
# @title: 卸载限速本地代理
# @description: 卸载 airopscat-ratelimit systemd 服务，清理 nftables 表、tc HTB 规则及所有相关文件

set -euo pipefail

NFT_TABLE="airopscat_ratelimit"
IPTS_CHAIN="AIROPSCAT_MARK"
DEFAULT_PATH="/etc/default/airopscat-ratelimit"
CONFIG_DIR="/etc/airopscat/ratelimit"
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

stop_services() {
  for svc in airopscat-ratelimit.service airopscat-connection-snapshot.service; do
    if systemctl is-active --quiet "${svc}" 2>/dev/null; then
      log "停止服务: ${svc}"
      systemctl stop "${svc}" 2>/dev/null || true
    fi
    if systemctl is-enabled --quiet "${svc}" 2>/dev/null; then
      log "禁用服务: ${svc}"
      systemctl disable "${svc}" 2>/dev/null || true
    fi
  done
}

detect_nic() {
  local nic=""

  if [[ -f "${DEFAULT_PATH}" ]]; then
    # shellcheck disable=SC1090
    source "${DEFAULT_PATH}" 2>/dev/null || true
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

  printf '%s\n' "${nic:-eth0}"
}

cleanup_tc() {
  if ! command -v tc >/dev/null 2>&1; then
    log "警告: 未找到 tc，跳过 HTB 规则清理"
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

  log "清理 iptables mangle 表中的遗留限速规则"
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

  if nft list table inet "${NFT_TABLE}" >/dev/null 2>&1; then
    log "删除 nftables 表: inet ${NFT_TABLE}"
    nft delete table inet "${NFT_TABLE}" 2>/dev/null || true
  fi
}

remove_files() {
  log "删除限速代理程序文件"
  rm -f \
    /usr/local/bin/airopscat-ratelimit-agent \
    /usr/local/bin/airopscat-connection-snapshot-agent \
    /etc/systemd/system/airopscat-ratelimit.service \
    /etc/systemd/system/airopscat-connection-snapshot.service \
    "${DEFAULT_PATH}"

  if [[ -d /run/airopscat ]]; then
    log "删除运行时快照目录: /run/airopscat"
    rm -rf /run/airopscat
  fi

  if ((PURGE_CONFIG == 1)); then
    log "删除限速账号配置目录: ${CONFIG_DIR}"
    rm -rf "${CONFIG_DIR}"
    if [[ -d /etc/airopscat ]] && [[ -z "$(ls -A /etc/airopscat 2>/dev/null)" ]]; then
      rmdir /etc/airopscat
    fi
  else
    log "保留限速账号配置目录: ${CONFIG_DIR}；如需删除请使用 --purge"
  fi
}

reload_systemd() {
  log "刷新 systemd 配置"
  systemctl daemon-reload
  systemctl reset-failed airopscat-ratelimit.service airopscat-connection-snapshot.service 2>/dev/null || true
}

main() {
  parse_args "$@"
  require_root
  stop_services
  cleanup_tc
  cleanup_iptables
  cleanup_nftables
  remove_files
  reload_systemd
  log "限速本地代理卸载完成"
}

main "$@"
