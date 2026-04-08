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

install_monitor_collector() {
  local target_dir="/usr/local/bin"
  local target_path="${target_dir}/airopscat-server-monitor-collect"

  log "安装服务器监控采集脚本到 ${target_path}"
  mkdir -p "${target_dir}"

  cat > "${target_path}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

read_cpu_values() {
  awk '/^cpu / {print $2" "$3" "$4" "$5" "$6" "$7" "$8}' /proc/stat
}

read_network_totals() {
  awk '
  NR > 2 {
    split($0, parts, ":");
    iface = parts[1];
    gsub(/^[ \t]+|[ \t]+$/, "", iface);
    if (iface == "" || iface ~ /^(lo|docker.*|veth.*|br.*|virbr.*|vmnet.*|zt.*)$/) {
      next;
    }
    gsub(/^[ \t]+/, "", parts[2]);
    count = split(parts[2], values, /[ \t]+/);
    rx += values[1];
    tx += values[9];
  }
  END {
    printf "%.0f %.0f\n", rx, tx;
  }' /proc/net/dev
}

calc_cpu_usage() {
  local before_values="$1"
  local after_values="$2"
  local -a before_parts
  local -a after_parts
  read -r -a before_parts <<< "${before_values}"
  read -r -a after_parts <<< "${after_values}"

  local total_before=0
  local total_after=0
  local item
  for item in "${before_parts[@]}"; do
    (( total_before += item ))
  done
  for item in "${after_parts[@]}"; do
    (( total_after += item ))
  done

  local idle_before="${before_parts[3]:-0}"
  local idle_after="${after_parts[3]:-0}"
  local delta_total=$(( total_after - total_before ))
  local delta_idle=$(( idle_after - idle_before ))

  if (( delta_total <= 0 )); then
    printf "0.00\n"
    return 0
  fi

  awk -v total="${delta_total}" -v idle="${delta_idle}" 'BEGIN {
    printf "%.2f\n", ((total - idle) * 100) / total
  }'
}

read_memory_stats() {
  awk '
  BEGIN {
    total = 0;
    available = 0;
  }
  /^MemTotal:/ {
    total = $2 * 1024;
  }
  /^MemAvailable:/ {
    available = $2 * 1024;
  }
  END {
    used = total - available;
    if (used < 0) {
      used = 0;
    }
    usage = total > 0 ? (used * 100 / total) : 0;
    printf "%.0f %.0f %.2f\n", used, total, usage;
  }' /proc/meminfo
}

cpu_before="$(read_cpu_values)"
read -r rx_before tx_before <<< "$(read_network_totals)"
sleep 1
cpu_after="$(read_cpu_values)"
read -r rx_after tx_after <<< "$(read_network_totals)"
read -r memory_used memory_total memory_usage <<< "$(read_memory_stats)"
cpu_usage="$(calc_cpu_usage "${cpu_before}" "${cpu_after}")"

rx_rate=$(( rx_after - rx_before ))
tx_rate=$(( tx_after - tx_before ))

if (( rx_rate < 0 )); then
  rx_rate=0
fi

if (( tx_rate < 0 )); then
  tx_rate=0
fi

printf "cpuUsage=%s\n" "${cpu_usage}"
printf "memoryUsage=%s\n" "${memory_usage}"
printf "memoryUsedBytes=%s\n" "${memory_used}"
printf "memoryTotalBytes=%s\n" "${memory_total}"
printf "networkRxBytes=%s\n" "${rx_after}"
printf "networkTxBytes=%s\n" "${tx_after}"
printf "networkRxRateBytes=%s\n" "${rx_rate}"
printf "networkTxRateBytes=%s\n" "${tx_rate}"
EOF

  chmod 0755 "${target_path}"
}

init_optimize() {
  # 关闭并删除已有 swapfile（若存在）
  if [ -f /swapfile ]; then
    swapoff /swapfile 2>/dev/null || true
    rm /swapfile
  fi

  log "创建 2G swap 文件"
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile

  log "写入 fstab 持久化"
  sed -i '\|^/swapfile\s|d' /etc/fstab && echo '/swapfile none swap sw 0 0' >> /etc/fstab
}

main() {
  require_root
  log "当前服务器变量: server_ip=${server_ip:-}, server_host=${server_host:-}"
  set_timezone
  install_packages
  install_monitor_collector
  init_optimize
  log "系统初始化完成"
}

main "$@"
