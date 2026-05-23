#!/usr/bin/env bash
# @title: 卸载用户态限速治理器
# @description: 卸载 airopscat-soft-ratelimit systemd 服务，仅清理用户态治理器文件，不影响旧 HTB 限速脚本和账号限速配置

set -euo pipefail

DEFAULT_PATH="/etc/default/airopscat-soft-ratelimit"

log() {
  printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    log "错误: 请使用 root 用户执行该脚本"
    exit 1
  fi
}

stop_service() {
  local svc="airopscat-soft-ratelimit.service"
  if systemctl is-active --quiet "${svc}" 2>/dev/null; then
    log "停止服务: ${svc}"
    systemctl stop "${svc}" 2>/dev/null || true
  fi
  if systemctl is-enabled --quiet "${svc}" 2>/dev/null; then
    log "禁用服务: ${svc}"
    systemctl disable "${svc}" 2>/dev/null || true
  fi
}

remove_files() {
  log "删除用户态限速治理器文件"
  rm -f \
    /usr/local/bin/airopscat-soft-ratelimit-agent \
    /etc/systemd/system/airopscat-soft-ratelimit.service \
    "${DEFAULT_PATH}"
}

reload_systemd() {
  log "刷新 systemd 配置"
  systemctl daemon-reload
  systemctl reset-failed airopscat-soft-ratelimit.service 2>/dev/null || true
}

main() {
  require_root
  stop_service
  remove_files
  reload_systemd
  log "用户态限速治理器卸载完成"
}

main "$@"
