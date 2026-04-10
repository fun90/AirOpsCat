#!/usr/bin/env bash
# @title: 安装限速本地代理
# @description: 安装 airopscat-ratelimit systemd 服务，在服务器本地每秒读取 sing-box 连接并维护限速规则

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

install_packages() {
  export DEBIAN_FRONTEND=noninteractive

  log "更新 APT 软件包索引"
  apt-get update

  log "安装限速代理依赖: python3 curl conntrack iproute2 iptables ethtool"
  apt-get install -y python3 curl conntrack iproute2 iptables ethtool
}

install_agent_files() {
  log "创建限速代理目录"
  mkdir -p /usr/local/bin /etc/airopscat/ratelimit /var/lib/airopscat-ratelimit

  if [[ ! -f /etc/airopscat/ratelimit/accounts.json ]]; then
    cat > /etc/airopscat/ratelimit/accounts.json <<'EOF'
{"accounts":[]}
EOF
  fi

  cat > /etc/default/airopscat-ratelimit <<'EOF'
# 可选配置，留空时自动探测默认网卡
AIROPSCAT_RATELIMIT_NIC=
AIROPSCAT_CLASH_API_PORT=19191
AIROPSCAT_RATELIMIT_INTERVAL=1
AIROPSCAT_RATELIMIT_ROOT_RATE=10000mbit
AIROPSCAT_RATELIMIT_DISABLE_OFFLOAD=true
EOF

  cat > /usr/local/bin/airopscat-ratelimit-agent <<'EOF'
#!/usr/bin/env python3
import json
import os
import ipaddress
import subprocess
import sys
import time
import urllib.error
import urllib.request

CONFIG_FILE = "/etc/airopscat/ratelimit/accounts.json"
STATE_FILE = "/var/lib/airopscat-ratelimit/state.json"
DEFAULT_NIC_ENV = "AIROPSCAT_RATELIMIT_NIC"
CLASH_API_PORT = os.getenv("AIROPSCAT_CLASH_API_PORT", "19191")
POLL_INTERVAL = max(1, int(os.getenv("AIROPSCAT_RATELIMIT_INTERVAL", "1")))
ROOT_RATE = os.getenv("AIROPSCAT_RATELIMIT_ROOT_RATE", "10000mbit")
DISABLE_OFFLOAD = os.getenv("AIROPSCAT_RATELIMIT_DISABLE_OFFLOAD", "true").strip().lower() not in {"0", "false", "no", "off"}
MAX_MARK_ID = 65533
RESERVED_DEFAULT_CLASS_MINOR = 9999


def log(message):
    print(time.strftime("[%Y-%m-%d %H:%M:%S]"), message, flush=True)


def run(command):
    return subprocess.run(
        command,
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def load_json_file(path, default_value):
    try:
        with open(path, "r", encoding="utf-8") as file:
            return json.load(file)
    except FileNotFoundError:
        return default_value
    except Exception as exc:
        log(f"读取 JSON 文件失败: {path}, error={exc}")
        return default_value


def save_state(state):
    with open(STATE_FILE, "w", encoding="utf-8") as file:
        json.dump(state, file, ensure_ascii=False, indent=2, sort_keys=True)


def detect_nic():
    nic = os.getenv(DEFAULT_NIC_ENV, "").strip()
    if nic:
        return nic

    result = run("ip route show default | awk 'NR==1 {for (i=1; i<=NF; i++) if ($i == \"dev\") {print $(i+1); exit}}'")
    nic = result.stdout.strip()
    return nic or "eth0"


def detect_ip_family(ip):
    try:
        return "ipv6" if ipaddress.ip_address(ip).version == 6 else "ipv4"
    except ValueError:
        return None


def resolve_class_minor(mark_id):
    class_minor = mark_id + 1
    if class_minor >= RESERVED_DEFAULT_CLASS_MINOR:
        class_minor += 1
    return class_minor if class_minor <= 65534 else None


def load_accounts():
    data = load_json_file(CONFIG_FILE, {"accounts": []})
    if isinstance(data, dict):
        items = data.get("accounts", [])
    elif isinstance(data, list):
        items = data
    else:
        items = []

    accounts = {}
    for item in items:
        if not isinstance(item, dict):
            continue
        account_no = str(item.get("accountNo") or "").strip()
        speed = item.get("speed")
        try:
            speed = int(speed)
        except (TypeError, ValueError):
            speed = 0
        if account_no and speed > 0:
            accounts[account_no] = speed
    return accounts


def allocate_marks(accounts, state):
    current_mapping = state.get("markByAccountNo", {})
    used_marks = set()
    next_mapping = {}

    for account_no in sorted(accounts):
        mark_id = current_mapping.get(account_no)
        if isinstance(mark_id, int) and 1 <= mark_id <= MAX_MARK_ID and mark_id not in used_marks and resolve_class_minor(mark_id):
            next_mapping[account_no] = mark_id
            used_marks.add(mark_id)

    next_mark = 1
    for account_no in sorted(accounts):
        if account_no in next_mapping:
            continue
        while next_mark in used_marks or resolve_class_minor(next_mark) is None:
            next_mark += 1
            if next_mark > MAX_MARK_ID:
                raise RuntimeError("可用限速 mark 已耗尽")
        next_mapping[account_no] = next_mark
        used_marks.add(next_mark)

    state["markByAccountNo"] = next_mapping
    return next_mapping


def ensure_restore_mark_rule(binary, chain):
    run(
        f"{binary} -t mangle -C {chain} -j CONNMARK --restore-mark 2>/dev/null "
        f"|| {binary} -t mangle -I {chain} -j CONNMARK --restore-mark"
    )


def ensure_offload_disabled(nic, state):
    if not DISABLE_OFFLOAD:
        return
    if state.get("offloadNic") == nic:
        return

    result = run(f"ethtool -K {nic} gro off gso off tso off 2>/dev/null")
    if result.returncode == 0:
        state["offloadNic"] = nic
    else:
        stderr = (result.stderr or "").strip()
        if stderr:
            log(f"关闭网卡 offload 失败: nic={nic}, stderr={stderr}")


def ensure_base_rules(nic, state):
    result = run(f"tc qdisc show dev {nic}")
    if "htb" not in result.stdout:
        run(f"tc qdisc del dev {nic} root 2>/dev/null || true")
        run(f"tc qdisc add dev {nic} root handle 1: htb default 9999")
        run(f"tc class add dev {nic} parent 1: classid 1:1 htb rate {ROOT_RATE}")
        run(f"tc class add dev {nic} parent 1:1 classid 1:9999 htb rate {ROOT_RATE}")

    ensure_offload_disabled(nic, state)
    ensure_restore_mark_rule("iptables", "PREROUTING")
    ensure_restore_mark_rule("iptables", "OUTPUT")
    ensure_restore_mark_rule("ip6tables", "PREROUTING")
    ensure_restore_mark_rule("ip6tables", "OUTPUT")


def list_existing_class_minors(nic):
    result = run(f"tc class show dev {nic}")
    class_minors = set()
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) < 3 or parts[0] != "class" or parts[1] != "htb":
            continue
        classid = parts[2]
        if not classid.startswith("1:"):
            continue
        try:
            class_minor = int(classid.split(":", 1)[1])
        except ValueError:
            continue
        if class_minor in (1, RESERVED_DEFAULT_CLASS_MINOR):
            continue
        class_minors.add(class_minor)
    return class_minors


def list_existing_filter_marks(nic):
    result = run(f"tc filter show dev {nic}")
    marks = set()
    for line in result.stdout.splitlines():
        if " handle " not in line or " fw " not in line:
            continue
        fragment = line.split(" handle ", 1)[1].split()[0]
        try:
            marks.add(int(fragment, 16 if fragment.startswith("0x") else 10))
        except ValueError:
            continue
    return marks


def sync_tc_profiles(nic, accounts, mark_mapping, state):
    current_profiles = {
        account_no: {"mark": mark_mapping[account_no], "speed": accounts[account_no]}
        for account_no in accounts
    }

    desired_marks = {profile["mark"] for profile in current_profiles.values()}
    desired_class_minors = {
        resolve_class_minor(profile["mark"])
        for profile in current_profiles.values()
        if resolve_class_minor(profile["mark"]) is not None
    }

    for mark_id in sorted(list_existing_filter_marks(nic) - desired_marks):
        run(f"tc filter del dev {nic} parent 1: handle {mark_id} fw 2>/dev/null || true")

    for class_minor in sorted(list_existing_class_minors(nic) - desired_class_minors, reverse=True):
        run(f"tc class del dev {nic} parent 1:1 classid 1:{class_minor} 2>/dev/null || true")

    for account_no, profile in current_profiles.items():
        mark_id = profile["mark"]
        class_minor = resolve_class_minor(mark_id)
        if class_minor is None:
            continue
        rate_kbit = profile["speed"] * 8
        run(
            f"tc class change dev {nic} parent 1:1 classid 1:{class_minor} htb rate {rate_kbit}kbit burst 32k 2>/dev/null "
            f"|| tc class add dev {nic} parent 1:1 classid 1:{class_minor} htb rate {rate_kbit}kbit burst 32k"
        )
        run(f"tc filter del dev {nic} parent 1: handle {mark_id} fw 2>/dev/null || true")
        run(f"tc filter add dev {nic} parent 1: handle {mark_id} fw flowid 1:{class_minor}")

    state["appliedProfiles"] = current_profiles


def fetch_connections():
    request = urllib.request.Request(
        f"http://127.0.0.1:{CLASH_API_PORT}/connections",
        headers={"Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=3) as response:
        return json.load(response)


def mark_connections(accounts, mark_mapping):
    if not accounts:
        return

    try:
        payload = fetch_connections()
    except urllib.error.URLError as exc:
        log(f"读取 sing-box 连接失败: {exc}")
        return
    except Exception as exc:
        log(f"解析 sing-box 连接失败: {exc}")
        return

    connections = payload.get("connections", [])
    for connection in connections:
        if not isinstance(connection, dict):
            continue
        metadata = connection.get("metadata") or {}
        account_no = str(metadata.get("authUser") or "").strip()
        if account_no not in accounts:
            continue

        source_ip = str(metadata.get("sourceIP") or "").strip()
        source_port = str(metadata.get("sourcePort") or "").strip()
        if not source_ip or not source_port.isdigit():
            continue

        family = detect_ip_family(source_ip)
        if family is None:
            continue

        protocol = "tcp" if str(metadata.get("network") or "").lower() == "tcp" else "udp"
        mark_id = mark_mapping.get(account_no)
        if mark_id is None:
            continue

        result = run(
            f"conntrack -U -f {family} -p {protocol} --src {source_ip} --sport {source_port} --mark {mark_id}"
        )
        if result.returncode != 0 and result.stderr:
            stderr = result.stderr.strip()
            if "0 flow entries have been updated" not in stderr:
                log(f"conntrack 打标失败: accountNo={account_no}, stderr={stderr}")


def main():
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    state = load_json_file(STATE_FILE, {})

    while True:
        try:
            nic = detect_nic()
            accounts = load_accounts()
            ensure_base_rules(nic, state)
            mark_mapping = allocate_marks(accounts, state)
            sync_tc_profiles(nic, accounts, mark_mapping, state)
            save_state(state)
            mark_connections(accounts, mark_mapping)
        except Exception as exc:
            log(f"限速代理执行失败: {exc}")
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
EOF

  chmod 0755 /usr/local/bin/airopscat-ratelimit-agent

  cat > /etc/systemd/system/airopscat-ratelimit.service <<'EOF'
[Unit]
Description=AirOpsCat Rate Limit Agent
After=network-online.target sing-box.service
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=-/etc/default/airopscat-ratelimit
ExecStart=/usr/local/bin/airopscat-ratelimit-agent
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
}

enable_service() {
  log "启用并启动 airopscat-ratelimit 服务"
  systemctl daemon-reload
  systemctl enable --now airopscat-ratelimit.service
  systemctl restart airopscat-ratelimit.service
}

main() {
  require_root
  install_packages
  install_agent_files
  enable_service
  log "限速本地代理安装完成"
}

main "$@"
