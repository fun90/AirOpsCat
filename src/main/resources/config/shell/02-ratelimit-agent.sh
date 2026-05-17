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
AIROPSCAT_CONNECTION_SNAPSHOT_INTERVAL=3
AIROPSCAT_CONNECTION_SNAPSHOT_DIR=/run/airopscat
AIROPSCAT_RATELIMIT_SNAPSHOT_TTL=10
AIROPSCAT_ONLINE_SNAPSHOT_TTL=60
AIROPSCAT_RATELIMIT_INTERVAL=3
AIROPSCAT_RATELIMIT_ROOT_RATE=10000mbit
AIROPSCAT_RATELIMIT_MAX_TC_CLASSES=512
EOF

  cat > /usr/local/bin/airopscat-connection-snapshot-agent <<'EOF'
#!/usr/bin/env python3
import http.client
import json
import os
import tempfile
import time


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
POLL_INTERVAL = env_int("AIROPSCAT_CONNECTION_SNAPSHOT_INTERVAL", 3, 1)
SNAPSHOT_DIR = os.getenv("AIROPSCAT_CONNECTION_SNAPSHOT_DIR", "/run/airopscat")
RATELIMIT_TTL = env_int("AIROPSCAT_RATELIMIT_SNAPSHOT_TTL", 10, 1)
ONLINE_TTL = env_int("AIROPSCAT_ONLINE_SNAPSHOT_TTL", 60, 1)
SCHEMA_VERSION = 1

RATELIMIT_FILE = os.path.join(SNAPSHOT_DIR, "ratelimit-flows.json")
ONLINE_FILE = os.path.join(SNAPSHOT_DIR, "online-connections.json")

_http_conn = None
_last_error_log_at = {}
_last_counts = None


def log(msg):
    print(time.strftime("[%Y-%m-%d %H:%M:%S]"), msg, flush=True)


def log_limited(key, msg, interval=60):
    now = time.monotonic()
    if now - _last_error_log_at.get(key, 0) >= interval:
        _last_error_log_at[key] = now
        log(msg)


def fetch_connections():
    global _http_conn
    for attempt in range(2):
        try:
            if _http_conn is None:
                _http_conn = http.client.HTTPConnection("127.0.0.1", CLASH_API_PORT, timeout=3)
            _http_conn.request("GET", "/connections", headers={"Accept": "application/json"})
            resp = _http_conn.getresponse()
            try:
                if resp.status < 200 or resp.status >= 300:
                    raise RuntimeError(f"Clash API status={resp.status}")
                return json.load(resp)
            finally:
                resp.read()
        except Exception:
            try:
                if _http_conn is not None:
                    _http_conn.close()
            except Exception:
                pass
            _http_conn = None
            if attempt == 1:
                raise


def resolve_node_tag(conn_type):
    if not isinstance(conn_type, str):
        return None
    slash = conn_type.find("/")
    if slash < 0 or slash >= len(conn_type) - 1:
        return None
    value = conn_type[slash + 1:].strip()
    return value or None


def build_snapshots(payload):
    now = int(time.time())
    flows = []
    online = []
    source_count = 0

    connections = payload.get("connections", []) if isinstance(payload, dict) else []
    for conn in connections:
        if not isinstance(conn, dict):
            continue
        source_count += 1
        meta = conn.get("metadata") or {}
        if not isinstance(meta, dict):
            continue

        auth_user = str(meta.get("authUser") or "").strip()
        source_ip = str(meta.get("sourceIP") or "").strip()
        network = str(meta.get("network") or "").strip().lower()
        source_port = meta.get("sourcePort")

        if auth_user and source_ip and network and source_port is not None:
            flows.append([network, source_ip, source_port, auth_user])

        if auth_user and source_ip:
            online.append({
                "id": conn.get("id"),
                "accountNo": auth_user,
                "clientIp": source_ip,
                "nodeTag": resolve_node_tag(meta.get("type")),
                "start": conn.get("start"),
            })

    return (
        {
            "schemaVersion": SCHEMA_VERSION,
            "generatedAtEpochSeconds": now,
            "ttlSeconds": RATELIMIT_TTL,
            "flows": flows,
        },
        {
            "schemaVersion": SCHEMA_VERSION,
            "generatedAtEpochSeconds": now,
            "ttlSeconds": ONLINE_TTL,
            "connections": online,
        },
        source_count,
        len(flows),
        len(online),
    )


def atomic_write_json(path, payload):
    os.makedirs(os.path.dirname(path), mode=0o750, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=os.path.basename(path) + ".", suffix=".tmp", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp_path, 0o640)
        os.replace(tmp_path, path)
    except Exception:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def main():
    global _last_counts
    os.makedirs(SNAPSHOT_DIR, mode=0o750, exist_ok=True)
    while True:
        try:
            started = time.monotonic()
            payload = fetch_connections()
            ratelimit_snapshot, online_snapshot, source_count, flow_count, online_count = build_snapshots(payload)
            atomic_write_json(RATELIMIT_FILE, ratelimit_snapshot)
            atomic_write_json(ONLINE_FILE, online_snapshot)
            counts = (source_count, flow_count, online_count)
            if counts != _last_counts:
                elapsed_ms = int((time.monotonic() - started) * 1000)
                log(f"connection snapshots written: source={source_count}, flows={flow_count}, online={online_count}, elapsedMs={elapsed_ms}")
                _last_counts = counts
        except Exception as exc:
            log_limited("snapshot", f"connection snapshot refresh failed: {exc}", 10)
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
EOF

  cat > /usr/local/bin/airopscat-ratelimit-agent <<'EOF'
#!/usr/bin/env python3
import ipaddress
import json
import os
import subprocess
import sys
import time

CONFIG_FILE = "/etc/airopscat/ratelimit/accounts.json"
SNAPSHOT_DIR = os.getenv("AIROPSCAT_CONNECTION_SNAPSHOT_DIR", "/run/airopscat")
RATELIMIT_SNAPSHOT_FILE = os.path.join(SNAPSHOT_DIR, "ratelimit-flows.json")
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


POLL_INTERVAL = env_int("AIROPSCAT_RATELIMIT_INTERVAL", 3, 1)
MAX_TC_CLASSES = env_int(
    "AIROPSCAT_RATELIMIT_MAX_TC_CLASSES",
    env_int("AIROPSCAT_RATELIMIT_MAX_ACTIVE_CLASSES", 512, 0, TC_CLASS_MINOR_MAX - 2),
    0,
    TC_CLASS_MINOR_MAX - 2,
)
SCHEMA_VERSION = 1

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


def load_ratelimit_snapshot():
    with open(RATELIMIT_SNAPSHOT_FILE, encoding="utf-8") as f:
        payload = json.load(f)
    if not isinstance(payload, dict):
        raise ValueError("snapshot is not an object")
    if payload.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"unsupported snapshot schema: {payload.get('schemaVersion')}")

    generated_at = int(payload.get("generatedAtEpochSeconds") or 0)
    ttl = int(payload.get("ttlSeconds") or 0)
    now = int(time.time())
    if generated_at <= 0 or ttl <= 0 or now - generated_at > ttl:
        raise ValueError(f"snapshot expired: age={now - generated_at}s ttl={ttl}s")

    flows = payload.get("flows")
    if not isinstance(flows, list):
        raise ValueError("snapshot flows is not a list")
    return flows


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


def select_limited_accounts(accounts):
    if not MAX_TC_CLASSES or len(accounts) <= MAX_TC_CLASSES:
        return dict(accounts)

    selected = {}
    for account_no in sorted(accounts)[:MAX_TC_CLASSES]:
        selected[account_no] = accounts[account_no]
    log_limited("tc_class_limit", f"HTB class 数量达到配置上限，仅为前 {len(selected)} 个账号创建 class: totalAccounts={len(accounts)}")
    return selected


def rebuild_tc(nic, tc_accounts):
    run(["tc", "qdisc", "del", "dev", nic, "root"])
    if not tc_accounts:
        return {}

    run(["tc", "qdisc", "add", "dev", nic, "root", "handle", "1:", "htb", "default", "9999", "r2q", "1"], check=True)
    run(["tc", "class", "add", "dev", nic, "parent", "1:", "classid", "1:1", "htb", "rate", ROOT_RATE,
         "burst", "1m", "cburst", "1m", "quantum", "1514"], check=True)
    run(["tc", "class", "add", "dev", nic, "parent", "1:1", "classid", "1:9999", "htb", "rate", ROOT_RATE,
         "burst", "1m", "cburst", "1m", "quantum", "1514"], check=True)

    marks = {}
    class_id = 2
    for account_no in sorted(tc_accounts):
        if class_id == 9999:
            class_id += 1
        if class_id > TC_CLASS_MINOR_MAX:
            log_limited("tc_class_limit", f"HTB class 数量达到上限，已跳过剩余账号: built={len(marks)}")
            break

        speed_kbit = tc_accounts[account_no] * 8
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


def desired_marks(flows, marks):
    desired = {"tcp4": {}, "udp4": {}, "tcp6": {}, "udp6": {}}
    for flow in flows:
        if not isinstance(flow, list) or len(flow) < 4:
            continue
        network, source_ip, source_port, account_no = flow[0], flow[1], flow[2], flow[3]
        mark = marks.get(str(account_no or "").strip())
        if not mark:
            continue

        key = nft_key(str(source_ip or "").strip(), str(source_port or "").strip())
        if key is None:
            continue

        network = str(network or "").lower()
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
    limited_accounts = {}
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
                limited_accounts = select_limited_accounts(accounts)
                marks = rebuild_tc(nic, limited_accounts)
                sync_nft_maps(prev_maps, empty_maps())
                log(f"已加载限速账号: totalAccounts={len(accounts)}, tcClasses={len(marks)}")

            if not accounts:
                time.sleep(POLL_INTERVAL)
                continue

            try:
                flows = load_ratelimit_snapshot()
            except Exception as exc:
                log_limited("snapshot", f"读取连接快照失败: {exc}")
                time.sleep(POLL_INTERVAL)
                continue

            sync_nft_maps(prev_maps, desired_marks(flows, marks))
        except Exception as exc:
            log_limited("main", f"限速代理执行失败: {exc}", 10)
            try:
                nic = detect_nic()
                setup_nft()
                prev_maps = empty_maps()
                marks = rebuild_tc(nic, limited_accounts)
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

  chmod 0755 /usr/local/bin/airopscat-connection-snapshot-agent /usr/local/bin/airopscat-ratelimit-agent

  cat > /etc/systemd/system/airopscat-connection-snapshot.service <<'EOF'
[Unit]
Description=AirOpsCat Connection Snapshot Agent
After=network-online.target sing-box.service
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=-/etc/default/airopscat-ratelimit
ExecStart=/usr/local/bin/airopscat-connection-snapshot-agent
Restart=always
RestartSec=2
Nice=10

[Install]
WantedBy=multi-user.target
EOF

  cat > /etc/systemd/system/airopscat-ratelimit.service <<'EOF'
[Unit]
Description=AirOpsCat Rate Limit Agent
After=network-online.target sing-box.service airopscat-connection-snapshot.service
Wants=network-online.target airopscat-connection-snapshot.service

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
  systemctl enable --now airopscat-connection-snapshot.service
  systemctl restart airopscat-connection-snapshot.service
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
