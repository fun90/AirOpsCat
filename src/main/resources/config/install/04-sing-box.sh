#!/usr/bin/env bash
# @title: 安装 sing-box
# @description: 安装 sing-box

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

install_sing_box() {
  local version="1.14.0"
  local package="sing-box_${version}_linux_amd64.deb"
  local url="https://github.com/fun90/sing-box/releases/download/${version}/${package}"

  log "下载 sing-box ${version}"
  wget -O "${package}" "${url}"

  log "安装 sing-box ${version}"
  dpkg -i "${package}"

  log "启用 sing-box 服务"
  systemctl enable sing-box
}

main() {
  require_root
  install_sing_box
  log "sing-box 安装完成"
}

main "$@"
