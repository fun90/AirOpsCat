#!/usr/bin/env bash
# @title: 申请并安装 TLS 证书
# @description: 使用 acme.sh 为 server_host 申请 Let's Encrypt 证书，并安装到 Xray 证书目录

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

require_server_host() {
  if [[ -z "${server_host:-}" ]]; then
    log "错误: 变量 server_host 不能为空"
    exit 1
  fi
}

install_dependencies() {
  export DEBIAN_FRONTEND=noninteractive
  log "安装证书签发依赖: curl wget socat"
  apt-get update
  apt-get install -y curl wget socat
}

install_acme() {
  local email="${ACME_EMAIL:-okbeok@gmail.com}"
  log "安装 acme.sh，注册邮箱: ${email}"
  curl -fsSL https://get.acme.sh -o /tmp/get-acme.sh
  sh /tmp/get-acme.sh email="${email}"

  export HOME="${HOME:-/root}"
  export PATH="${HOME}/.acme.sh:${PATH}"
  if [[ -f "${HOME}/.bashrc" ]]; then
    # shellcheck disable=SC1090
    source "${HOME}/.bashrc" || true
  fi
  "${HOME}/.acme.sh/acme.sh" --set-default-ca --server letsencrypt
  rm -f /tmp/get-acme.sh
}

issue_certificate() {
  local -a domains=()
  local -a issue_args=()

  if declare -p server_hosts >/dev/null 2>&1 && [[ ${#server_hosts[@]} -gt 0 ]]; then
    domains=("${server_hosts[@]}")
  elif [[ -n "${server_host:-}" ]]; then
    domains=("${server_host}")
  fi

  if [[ ${#domains[@]} -eq 0 ]]; then
    log "错误: 未找到可申请证书的域名"
    exit 1
  fi

  for domain in "${domains[@]}"; do
    issue_args+=(-d "${domain}")
  done

  log "为域名申请证书: ${domains[*]}"
  "${HOME}/.acme.sh/acme.sh" --issue --force "${issue_args[@]}" --standalone
}

install_certificate() {
  local -a domains=()
  local cert_root="/usr/local/etc/certs"

  if declare -p server_hosts >/dev/null 2>&1 && [[ ${#server_hosts[@]} -gt 0 ]]; then
    domains=("${server_hosts[@]}")
  elif [[ -n "${server_host:-}" ]]; then
    domains=("${server_host}")
  fi

  if [[ ${#domains[@]} -eq 0 ]]; then
    log "错误: 未找到可安装证书的域名"
    exit 1
  fi

  log "按域名单独安装证书到 ${cert_root}"
  mkdir -p "${cert_root}"

  local domain=""
  local cert_dir=""
  for domain in "${domains[@]}"; do
    cert_dir="${cert_root}/${domain}"
    mkdir -p "${cert_dir}"
    "${HOME}/.acme.sh/acme.sh" --install-cert -d "${domain}" \
      --key-file "${cert_dir}/private.key" \
      --fullchain-file "${cert_dir}/fullchain.cer"

    chmod 644 "${cert_dir}/private.key"
    log "域名 ${domain} 的证书已安装到 ${cert_dir}"
  done
}

main() {
  require_root
  require_server_host
  log "当前服务器变量: server_ip=${server_ip:-}, server_host=${server_host}"
  install_dependencies
  install_acme
  issue_certificate
  install_certificate
  log "证书申请和安装完成"
}

main "$@"
