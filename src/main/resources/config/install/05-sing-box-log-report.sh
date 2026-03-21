#!/usr/bin/env bash
# @title: 配置 sing-box 日志上报
# @description: 生成 sing-box 访问日志分析脚本并配置定时上报和日志清理任务，API 域名与 Token 来自系统配置

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

write_report_script() {
  log "写入 /root/singbox_log_report.sh"
  cat > /root/singbox_log_report.sh << 'EOF'
#!/usr/bin/env bash

set -euo pipefail

LAST_POS_FILE="/root/singbox_last_pos.log"
LOG_INPUT_FILE="/var/lib/sing-box/box.log"
API_URL="https://${airopscat_domain}/api/open/account/online"
SERVER_IP="${server_ip:-}"
AIROPSCAT_DOMAIN="${airopscat_domain:-}"
AIROPSCAT_API_TOKEN="${airopscat_api_token:-}"

IGNORE_DOMAINS=(
    "cp.cloudflare.com"
    "www.gstatic.com"
)

is_domain_ignored() {
    local domain="$1"
    for ignored_domain in "${IGNORE_DOMAINS[@]}"; do
        if [[ "$domain" == "$ignored_domain" ]]; then
            return 0
        fi
    done
    return 1
}

send_request() {
    local client_ip="$1"
    local user_tag="$2"
    local report_server_ip="$3"

    local response
    response=$(curl -sS -w "%{http_code}" -X POST "${API_URL}/${report_server_ip}" \
        -H "Content-Type: application/json" \
        -H "Token: ${AIROPSCAT_API_TOKEN}" \
        -d "{\"clientIp\":\"${client_ip}\",\"accountNo\":\"${user_tag}\"}")

    local http_code="${response: -3}"
    local response_body="${response%???}"

    if [[ "${http_code}" -ge 200 && "${http_code}" -lt 300 ]]; then
        echo "Successfully sent request for ${client_ip} (${user_tag})"
    else
        echo "Failed to send request for ${client_ip} (${user_tag}) - HTTP ${http_code}"
        echo "Response: ${response_body}"
    fi
}

main() {
    if [[ -z "${SERVER_IP}" ]]; then
        echo "Error: server_ip is empty"
        exit 1
    fi

    if [[ -z "${AIROPSCAT_DOMAIN}" ]]; then
        echo "Error: airopscat_domain is empty"
        exit 1
    fi

    if [[ -z "${AIROPSCAT_API_TOKEN}" ]]; then
        echo "Error: airopscat_api_token is empty"
        exit 1
    fi

    if [[ ! -f "${LOG_INPUT_FILE}" ]]; then
        echo "Error: Log file ${LOG_INPUT_FILE} not found"
        exit 1
    fi

    local last_pos=0
    if [[ -f "${LAST_POS_FILE}" ]]; then
        last_pos=$(cat "${LAST_POS_FILE}")
    fi

    # 关联缓存：conn_id -> client_ip
    # sing-box 日志结构：
    #   inbound connection from <IP>:<port>         (无用户 tag，记录 conn_id -> IP)
    #   [<user_tag>] inbound connection to <domain> (有用户 tag，查 conn_id -> IP)
    declare -A conn_ip_map

    local temp_file
    temp_file=$(mktemp)

    local total_lines=0
    local processed_lines=0
    local ignored_lines=0
    local duplicate_lines=0

    while IFS= read -r line; do
        total_lines=$((total_lines + 1))

        # 提取连接 ID（方括号内纯数字，位于日志级别之后）
        # 格式示例：INFO [423627595 245ms] inbound/vless[node_39]: ...
        local conn_id=""
        if [[ "$line" =~ \[([0-9]+)[[:space:]][0-9]+ms\] ]]; then
            conn_id="${BASH_REMATCH[1]}"
        else
            continue
        fi

        # 匹配 "inbound connection from <IP>:<port>" → 记录 conn_id -> IP
        if [[ "$line" =~ inbound\ connection\ from\ ([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+):[0-9]+ ]]; then
            conn_ip_map["${conn_id}"]="${BASH_REMATCH[1]}"
            continue
        fi

        # 匹配 "[<user_tag>] inbound connection to <domain>:<port>"
        # user_tag 紧跟在 ": " 之后（即 "inbound/vless[node_39]: [wkfxv0n] inbound connection to"）
        # 用 ":\ \[" 锚定，避免误匹配 inbound/vless[node_39] 中的节点名
        if [[ "$line" =~ :[[:space:]]\[([a-zA-Z0-9_-]+)\][[:space:]]inbound[[:space:]]connection[[:space:]]to[[:space:]]([^:]+):[0-9]+ ]]; then
            local user_tag="${BASH_REMATCH[1]}"
            local domain="${BASH_REMATCH[2]}"

            # 跳过纯数字 tag（避免误匹配 conn_id 自身）
            if [[ "$user_tag" =~ ^[0-9]+$ ]]; then
                continue
            fi

            local client_ip="${conn_ip_map[${conn_id}]:-}"
            if [[ -z "${client_ip}" ]]; then
                echo "Warning: No client IP found for conn_id=${conn_id}, skipping"
                continue
            fi

            if is_domain_ignored "${domain}"; then
                ignored_lines=$((ignored_lines + 1))
                continue
            fi

            if ! grep -qF "${client_ip} ${user_tag}" "${temp_file}"; then
                send_request "${client_ip}" "${user_tag}" "${SERVER_IP}"
                echo "${client_ip} ${user_tag}" >> "${temp_file}"
                processed_lines=$((processed_lines + 1))
            else
                duplicate_lines=$((duplicate_lines + 1))
            fi
        fi
    done < <(tail -n +"$((last_pos + 1))" "${LOG_INPUT_FILE}")

    rm -f "${temp_file}"

    local current_line_count
    current_line_count=$(wc -l < "${LOG_INPUT_FILE}")
    echo "${current_line_count}" > "${LAST_POS_FILE}"

    echo "=== Processing Summary ==="
    echo "Total lines processed: ${total_lines}"
    echo "Successfully processed: ${processed_lines}"
    echo "Ignored (blocked domains): ${ignored_lines}"
    echo "Duplicates skipped: ${duplicate_lines}"
    echo "Current log position: ${current_line_count}"
    echo "=========================="
}

main "$@"
EOF

  chmod +x /root/singbox_log_report.sh
}

configure_cron() {
  log "配置 sing-box 日志上报和清理定时任务"
  (
    crontab -l 2>/dev/null \
      | grep -F -v '/root/singbox_log_report.sh' \
      | grep -F -v '/var/lib/sing-box/box.log' \
      | grep -F -v '/root/singbox_last_pos.log' \
      || true
    echo '0 5 * * * truncate -s 0 /var/lib/sing-box/box.log'
    echo '0 5 * * * truncate -s 0 /root/singbox_last_pos.log'
    echo "*/2 * * * * server_ip=${server_ip:-} airopscat_domain=${airopscat_domain:-} airopscat_api_token=${airopscat_api_token:-} /root/singbox_log_report.sh"
  ) | crontab -
}

main() {
  require_root
  write_report_script
  configure_cron
  log "sing-box 日志上报配置完成"
}

main "$@"