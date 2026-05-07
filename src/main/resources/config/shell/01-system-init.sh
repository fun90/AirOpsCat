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
  apt-get update && apt-get upgrade -y

  log "安装基础软件包: cron vim wget htop conntrack iftop"
  apt-get install -y cron vim wget htop conntrack iftop
}

install_monitor_collector() {
  local target_dir="/usr/local/bin"
  local target_path="${target_dir}/airopscat-server-monitor-collect"

  log "安装服务器监控采集脚本到 ${target_path}"
  mkdir -p "${target_dir}"

  cat > "${target_path}" <<'EOF'
#!/usr/bin/env bash
# 采集策略：将上次采集的 CPU 和网络计数器持久化到状态文件，
# 本次采集时读取差值并除以实际间隔秒数计算速率，无需 sleep。
set -euo pipefail

STATE_FILE="/var/run/airopscat-monitor-state"

read_cpu_values() {
  # 字段顺序: user nice system idle iowait irq softirq steal
  # steal($9) 是虚拟机被宿主机抢占的时间，htop 将其计为 busy，必须读入
  # guest/guest_nice($10/$11) 已包含在 user/nice 内，不重复读取
  awk '/^cpu / {print $2" "$3" "$4" "$5" "$6" "$7" "$8" "$9}' /proc/stat
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

  # idle_total = idle(3) + iowait(4)
  # iowait 是等待 IO 完成，CPU 实际空闲，htop 不将其计为 busy，与此保持一致
  local idle_before=$(( ${before_parts[3]:-0} + ${before_parts[4]:-0} ))
  local idle_after=$(( ${after_parts[3]:-0}  + ${after_parts[4]:-0} ))
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

# 读取当前快照
now=$(date +%s)
cpu_now="$(read_cpu_values)"
read -r rx_now tx_now <<< "$(read_network_totals)"
read -r memory_used memory_total memory_usage <<< "$(read_memory_stats)"

# 解析状态文件（若存在），计算差值速率
cpu_usage="0.00"
rx_rate=0
tx_rate=0

if [[ -f "${STATE_FILE}" ]]; then
  prev_time=0
  prev_cpu=""
  prev_rx=0
  prev_tx=0

  while IFS='=' read -r key value; do
    case "${key}" in
      prev_time) prev_time="${value}" ;;
      prev_cpu)  prev_cpu="${value}" ;;
      prev_rx)   prev_rx="${value}" ;;
      prev_tx)   prev_tx="${value}" ;;
    esac
  done < "${STATE_FILE}"

  elapsed=$(( now - prev_time ))

  if (( elapsed > 0 && prev_time > 0 )); then
    cpu_usage="$(calc_cpu_usage "${prev_cpu}" "${cpu_now}")"

    # 处理计数器回绕（重启后归零）：回绕时速率视为 0
    if (( rx_now >= prev_rx )); then
      rx_rate=$(( (rx_now - prev_rx) / elapsed ))
    fi
    if (( tx_now >= prev_tx )); then
      tx_rate=$(( (tx_now - prev_tx) / elapsed ))
    fi
  fi
fi

# 将当前快照写入状态文件供下次采集使用
{
  printf "prev_time=%s\n" "${now}"
  printf "prev_cpu=%s\n"  "${cpu_now}"
  printf "prev_rx=%s\n"   "${rx_now}"
  printf "prev_tx=%s\n"   "${tx_now}"
} > "${STATE_FILE}"

printf "cpuUsage=%s\n"           "${cpu_usage}"
printf "memoryUsage=%s\n"        "${memory_usage}"
printf "memoryUsedBytes=%s\n"    "${memory_used}"
printf "memoryTotalBytes=%s\n"   "${memory_total}"
printf "networkRxBytes=%s\n"     "${rx_now}"
printf "networkTxBytes=%s\n"     "${tx_now}"
printf "networkRxRateBytes=%s\n" "${rx_rate}"
printf "networkTxRateBytes=%s\n" "${tx_rate}"
EOF

  chmod 0755 "${target_path}"
}

main() {
  require_root
  log "当前服务器变量: server_ip=${server_ip:-}, server_host=${server_host:-}"
  set_timezone
  install_packages
  install_monitor_collector
  log "系统初始化完成"
}

main "$@"
