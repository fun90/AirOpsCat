#!/usr/bin/env bash
# @title: 安装限速本地代理
# @description: 安装 airopscat-ratelimit systemd 服务，使用 sing-box 连接列表、nftables map 和 tc HTB 维护账号限速

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

  log "安装限速代理依赖: python3 iproute2 nftables"
  apt-get install -y python3 iproute2 nftables
}

install_agent_files() {
  log "创建限速代理目录"
  mkdir -p /usr/local/bin /etc/airopscat/ratelimit

  if [[ ! -f /etc/airopscat/ratelimit/accounts.json ]]; then
    cat > /etc/airopscat/ratelimit/accounts.json <<'EOF'
{"accounts":[]}
EOF
  fi

  cat > /etc/default/airopscat-ratelimit <<'EOF'
# 可选配置，留空时自动探测默认网卡
AIROPSCAT_RATELIMIT_NIC=
AIROPSCAT_CLASH_API_PORT=19191
AIROPSCAT_RATELIMIT_INTERVAL=3
AIROPSCAT_RATELIMIT_ROOT_RATE=10000mbit
AIROPSCAT_RATELIMIT_ACTIVE_TTL=15
AIROPSCAT_RATELIMIT_MAX_ACTIVE_CLASSES=512
EOF

  cat > /usr/local/bin/airopscat-ratelimit-agent <<'EOF'
#!/usr/bin/env python3
import http.client
import ipaddress
import json
import os
import subprocess
import sys
import time

CONFIG_FILE = "/etc/airopscat/ratelimit/accounts.json"
NFT_TABLE = "airopscat_ratelimit"
NFT_LEGACY_CHAIN = "AIROPSCAT_MARK"
TC_CLASS_MINOR_MAX = 65534
ROOT_RATE = os.getenv("AIROPSCAT_RATELIMIT_ROOT_RATE", "10000mbit")


def env_int(name, default, minimum=None, maximum=None):
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    if minimum is not None:
        value = max(minimum, value)
    if maximum is not None:
        value = min(maximum, value)
    return value


CLASH_API_PORT = env_int("AIROPSCAT_CLASH_API_PORT", 19191, 1, 65535)
POLL_INTERVAL = env_int("AIROPSCAT_RATELIMIT_INTERVAL", 3, 1)
ACTIVE_TTL = max(POLL_INTERVAL, env_int("AIROPSCAT_RATELIMIT_ACTIVE_TTL", 15, 1))
MAX_ACTIVE_CLASSES = env_int("AIROPSCAT_RATELIMIT_MAX_ACTIVE_CLASSES", 512, 0, TC_CLASS_MINOR_MAX - 2)

_http_conn = None
_last_error_log_at = {}


def log(msg):
    print(time.strftime("[%Y-%m-%d %H:%M:%S]"), msg, flush=True)


def log_limited(key, msg, interval=60):
    now = time.monotonic()
    if now - _last_error_log_at.get(key, 0) >= interval:
        _last_error_log_at[key] = now
        log(msg)


def run(args, input_text=None, check=False):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True)
    if check and result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or " ".join(args))
    return result


def fetch_connections():
    global _http_conn
    for attempt in range(2):
        try:
            if _http_conn is None:
                _http_conn = http.client.HTTPConnection("127.0.0.1", CLASH_API_PORT, timeout=3)
            _http_conn.request("GET", "/connections", headers={"Accept": "application/json"})
            resp = _http_conn.getresponse()
            try:
                return json.load(resp)
            finally:
                resp.read()
        except Exception:
            try:
                _http_conn.close()
            except Exception:
                pass
            _http_conn = None
            if attempt == 1:
                raise


def detect_nic():
    nic = os.getenv("AIROPSCAT_RATELIMIT_NIC", "").strip()
    if nic:
        return nic
    result = run(["ip", "route", "show", "default"])
    tokens = result.stdout.split()
    if "dev" in tokens:
        return tokens[tokens.index("dev") + 1]
    return "eth0"


def load_accounts():
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        return {}

    items = data.get("accounts", []) if isinstance(data, dict) else data
    if not isinstance(items, list):
        return {}

    accounts = {}
    for item in items:
        if not isinstance(item, dict):
            continue
        account_no = str(item.get("accountNo") or "").strip()
        try:
            speed = int(item.get("speed") or 0)
        except (TypeError, ValueError):
            speed = 0
        if account_no and speed > 0:
            accounts[account_no] = speed
    return accounts


def calc_burst_k(speed_kbit):
    bytes_per_sec = speed_kbit * 1000 // 8
    return max(128, max(128 * 1024, bytes_per_sec) // 1024)


def build_active_accounts(payload, accounts, last_seen):
    now = time.monotonic()
    counts = {}
    for conn in payload.get("connections", []):
        if not isinstance(conn, dict):
            continue
        account_no = str((conn.get("metadata") or {}).get("authUser") or "").strip()
        if account_no in accounts:
            last_seen[account_no] = now
            counts[account_no] = counts.get(account_no, 0) + 1

    active = []
    for account_no, seen_at in list(last_seen.items()):
        if account_no not in accounts or now - seen_at > ACTIVE_TTL:
            last_seen.pop(account_no, None)
            continue
        active.append((account_no, seen_at, counts.get(account_no, 0)))

    if MAX_ACTIVE_CLASSES and len(active) > MAX_ACTIVE_CLASSES:
        active.sort(key=lambda item: (item[2] > 0, item[2], item[1], item[0]), reverse=True)
        for account_no, _, count in active[MAX_ACTIVE_CLASSES:]:
            if count == 0:
                last_seen.pop(account_no, None)
        active = active[:MAX_ACTIVE_CLASSES]

    return {account_no: accounts[account_no] for account_no, _, _ in active}


def rebuild_tc(nic, active_accounts):
    run(["tc", "qdisc", "del", "dev", nic, "root"])
    if not active_accounts:
        return {}

    run(["tc", "qdisc", "add", "dev", nic, "root", "handle", "1:", "htb", "default", "9999", "r2q", "1"], check=True)
    run(["tc", "class", "add", "dev", nic, "parent", "1:", "classid", "1:1", "htb", "rate", ROOT_RATE,
         "burst", "1m", "cburst", "1m", "quantum", "1514"], check=True)
    run(["tc", "class", "add", "dev", nic, "parent", "1:1", "classid", "1:9999", "htb", "rate", ROOT_RATE,
         "burst", "1m", "cburst", "1m", "quantum", "1514"], check=True)

    marks = {}
    class_id = 2
    for account_no in sorted(active_accounts):
        if class_id == 9999:
            class_id += 1
        if class_id > TC_CLASS_MINOR_MAX:
            log_limited("tc_class_limit", f"HTB class 数量达到上限，已跳过剩余账号: built={len(marks)}")
            break

        speed_kbit = active_accounts[account_no] * 8
        burst_k = f"{calc_burst_k(speed_kbit)}k"
        class_result = run(["tc", "class", "add", "dev", nic, "parent", "1:1", "classid", f"1:{class_id}",
                            "htb", "rate", f"{speed_kbit}kbit", "burst", burst_k, "cburst", burst_k, "quantum", "1514"])
        if class_result.returncode != 0:
            log_limited(f"class_{account_no}", f"tc class 添加失败: account={account_no}, stderr={class_result.stderr.strip()}")
            class_id += 1
            continue

        filter_result = run(["tc", "filter", "add", "dev", nic, "parent", "1:", "handle", str(class_id), "fw", "flowid", f"1:{class_id}"])
        if filter_result.returncode != 0:
            run(["tc", "class", "del", "dev", nic, "classid", f"1:{class_id}"])
            log_limited(f"filter_{account_no}", f"tc filter 添加失败: account={account_no}, stderr={filter_result.stderr.strip()}")
            class_id += 1
            continue

        marks[account_no] = class_id
        class_id += 1
    return marks


def cleanup_legacy_iptables():
    if run(["sh", "-c", "command -v iptables >/dev/null 2>&1"]).returncode != 0:
        return
    commands = [
        f"while iptables -t mangle -C OUTPUT -j {NFT_LEGACY_CHAIN} 2>/dev/null; do iptables -t mangle -D OUTPUT -j {NFT_LEGACY_CHAIN}; done",
        "while iptables -t mangle -C OUTPUT -j CONNMARK --restore-mark 2>/dev/null; do iptables -t mangle -D OUTPUT -j CONNMARK --restore-mark; done",
        "while iptables -t mangle -C PREROUTING -j CONNMARK --restore-mark 2>/dev/null; do iptables -t mangle -D PREROUTING -j CONNMARK --restore-mark; done",
        f"iptables -t mangle -F {NFT_LEGACY_CHAIN} 2>/dev/null || true",
        f"iptables -t mangle -X {NFT_LEGACY_CHAIN} 2>/dev/null || true",
    ]
    run(["sh", "-c", "\n".join(commands)])


def setup_nft():
    rules = f"""
table inet {NFT_TABLE} {{
  map tcp4 {{ type ipv4_addr . inet_service : mark; }}
  map udp4 {{ type ipv4_addr . inet_service : mark; }}
  map tcp6 {{ type ipv6_addr . inet_service : mark; }}
  map udp6 {{ type ipv6_addr . inet_service : mark; }}
  chain output {{
    type route hook output priority mangle; policy accept;
    ip protocol tcp meta mark set ip daddr . tcp dport map @tcp4
    ip protocol udp meta mark set ip daddr . udp dport map @udp4
    ip6 nexthdr tcp meta mark set ip6 daddr . tcp dport map @tcp6
    ip6 nexthdr udp meta mark set ip6 daddr . udp dport map @udp6
  }}
}}
"""
    run(["nft", "delete", "table", "inet", NFT_TABLE])
    run(["nft", "-f", "-"], input_text=rules, check=True)


def nft_key(ip_text, port_text):
    try:
        ip_obj = ipaddress.ip_address(ip_text)
    except ValueError:
        return None
    if not port_text.isdigit():
        return None
    port = int(port_text)
    if port < 1 or port > 65535:
        return None
    family = "6" if ip_obj.version == 6 else "4"
    return family, f"{ip_obj.compressed} . {port}"


def desired_marks(payload, marks):
    desired = {"tcp4": {}, "udp4": {}, "tcp6": {}, "udp6": {}}
    for conn in payload.get("connections", []):
        if not isinstance(conn, dict):
            continue
        meta = conn.get("metadata") or {}
        mark = marks.get(str(meta.get("authUser") or "").strip())
        if not mark:
            continue

        key = nft_key(str(meta.get("sourceIP") or "").strip(), str(meta.get("sourcePort") or "").strip())
        if key is None:
            continue

        network = str(meta.get("network") or "").lower()
        proto = "tcp" if network == "tcp" else "udp"
        family, value = key
        desired[f"{proto}{family}"][value] = mark
    return desired


def sync_nft_maps(prev, desired):
    lines = []
    for map_name in ("tcp4", "udp4", "tcp6", "udp6"):
        old_items = prev[map_name]
        new_items = desired[map_name]

        deletes = [key for key, mark in old_items.items() if key not in new_items or new_items[key] != mark]
        adds = [(key, mark) for key, mark in new_items.items() if old_items.get(key) != mark]

        if deletes:
            lines.append(f"delete element inet {NFT_TABLE} {map_name} {{ " + ", ".join(deletes) + " }")
        if adds:
            body = ", ".join(f"{key} : {mark}" for key, mark in adds)
            lines.append(f"add element inet {NFT_TABLE} {map_name} {{ {body} }}")

    if lines:
        run(["nft", "-f", "-"], input_text="\n".join(lines) + "\n", check=True)
        for map_name in prev:
            prev[map_name] = dict(desired[map_name])


def empty_maps():
    return {"tcp4": {}, "udp4": {}, "tcp6": {}, "udp6": {}}


def main():
    nic = detect_nic()
    accounts_mtime = None
    accounts = {}
    active_accounts = {}
    last_seen = {}
    marks = {}
    prev_maps = empty_maps()

    cleanup_legacy_iptables()
    setup_nft()

    while True:
        try:
            try:
                mtime = os.path.getmtime(CONFIG_FILE)
            except OSError:
                mtime = None

            if mtime != accounts_mtime:
                accounts = load_accounts()
                accounts_mtime = mtime
                last_seen = {key: value for key, value in last_seen.items() if key in accounts}
                active_accounts = {}
                marks = rebuild_tc(nic, {})
                sync_nft_maps(prev_maps, empty_maps())
                log(f"已加载限速账号: totalAccounts={len(accounts)}")

            if not accounts:
                time.sleep(POLL_INTERVAL)
                continue

            try:
                payload = fetch_connections()
            except Exception as exc:
                log_limited("fetch", f"读取 sing-box 连接失败: {exc}")
                time.sleep(POLL_INTERVAL)
                continue

            next_active = build_active_accounts(payload, accounts, last_seen)
            if next_active != active_accounts:
                active_accounts = next_active
                marks = rebuild_tc(nic, active_accounts)
                sync_nft_maps(prev_maps, empty_maps())
                log(f"已刷新限速 class: activeAccounts={len(active_accounts)}, totalAccounts={len(accounts)}")

            sync_nft_maps(prev_maps, desired_marks(payload, marks))
        except Exception as exc:
            log_limited("main", f"限速代理执行失败: {exc}", 10)
            try:
                nic = detect_nic()
                setup_nft()
                prev_maps = empty_maps()
                marks = rebuild_tc(nic, active_accounts)
                sync_nft_maps(prev_maps, empty_maps())
            except Exception as recover_exc:
                log_limited("recover", f"限速代理恢复失败: {recover_exc}", 10)
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
Nice=10

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
