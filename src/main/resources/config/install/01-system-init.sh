#!/usr/bin/env bash
# @title: 系统初始化
# @description: 设置时区并安装基础工具 cron、vim、wget，示例展示如何使用 server_ip 和 server_host 变量

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

set_timezone() {
  local timezone="Asia/Shanghai"
  if command -v timedatectl >/dev/null 2>&1; then
    log "设置系统时区为 ${timezone}"
    timedatectl set-timezone "${timezone}"
  else
    log "警告: 未找到 timedatectl，跳过时区设置"
  fi
}

install_packages() {
  export DEBIAN_FRONTEND=noninteractive

  log "更新 APT 软件包索引"
  apt-get update

  log "安装基础软件包: cron vim wget"
  apt-get install -y cron vim wget
}

main() {
  require_root
  log "当前服务器变量: server_ip=${server_ip:-}, server_host=${server_host:-}"
  set_timezone
  install_packages
  log "系统初始化完成"
}

main "$@"
