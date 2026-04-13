#!/usr/bin/env bash
# @title: 安装限速本地代理
# @description: 安装 airopscat-ratelimit systemd 服务，在服务器本地每隔X秒读取 sing-box 连接并维护限速规则

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

  log "安装限速代理依赖: python3 curl conntrack iproute2 iptables"
  apt-get install -y python3 curl conntrack iproute2 iptables
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
AIROPSCAT_RATELIMIT_INTERVAL=5
AIROPSCAT_RATELIMIT_ROOT_RATE=10000mbit
EOF

  cat > /usr/local/bin/airopscat-ratelimit-agent <<'EOF'
#!/usr/bin/env python3
import http.client
import json
import os
import subprocess
import sys
import time

CONFIG_FILE = "/etc/airopscat/ratelimit/accounts.json"
CLASH_API_PORT = int(os.getenv("AIROPSCAT_CLASH_API_PORT", "19191"))
POLL_INTERVAL = max(1, int(os.getenv("AIROPSCAT_RATELIMIT_INTERVAL", "3")))
ROOT_RATE = os.getenv("AIROPSCAT_RATELIMIT_ROOT_RATE", "10000mbit")

# 持久 HTTP 连接，避免每轮重建 TCP 连接
_http_conn = None


def log(msg):
    print(time.strftime("[%Y-%m-%d %H:%M:%S]"), msg, flush=True)


def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)


def fetch_connections():
    """通过持久 HTTP 连接获取 sing-box 连接列表，减少系统调用开销。"""
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
                resp.read()  # 排尽响应体，保持连接可复用
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
    tokens = subprocess.run(["ip", "route", "show", "default"], capture_output=True, text=True).stdout.split()
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
    result = {}
    for item in items:
        if not isinstance(item, dict):
            continue
        account_no = str(item.get("accountNo") or "").strip()
        try:
            speed = int(item.get("speed") or 0)
        except (TypeError, ValueError):
            speed = 0
        if account_no and speed > 0:
            result[account_no] = speed
    return result


def calc_burst(speed_kbit):
    """根据速率动态计算 burst，避免 HTB too many events。
    burst = max(64k, 速率对应的字节数 / 10)，单位 kB。"""
    rate_bytes_per_sec = speed_kbit * 1000 // 8
    burst_bytes = max(64 * 1024, rate_bytes_per_sec // 10)
    return max(64, burst_bytes // 1024)


def rebuild_tc(nic, accounts):
    """删除并重建 HTB 规则，返回 account_no -> mark_id 映射。仅在账号变化时调用。"""
    run(f"tc qdisc del dev {nic} root 2>/dev/null || true")
    if not accounts:
        return {}

    run(f"tc qdisc add dev {nic} root handle 1: htb default 9999")
    run(f"tc class add dev {nic} parent 1: classid 1:1 htb rate {ROOT_RATE} burst 128k quantum 1514")
    run(f"tc class add dev {nic} parent 1:1 classid 1:9999 htb rate {ROOT_RATE} burst 128k quantum 1514")

    marks = {}
    class_id = 2
    for account_no in sorted(accounts):
        if class_id == 9999:
            class_id += 1
        speed_kbit = accounts[account_no] * 8
        burst_k = calc_burst(speed_kbit)
        run(f"tc class add dev {nic} parent 1:1 classid 1:{class_id} htb rate {speed_kbit}kbit burst {burst_k}k cburst {burst_k}k quantum 1514")
        run(f"tc filter add dev {nic} parent 1: handle {class_id} fw flowid 1:{class_id}")
        marks[account_no] = class_id
        class_id += 1
    return marks


def setup_iptables():
    run("iptables -t mangle -C PREROUTING -j CONNMARK --restore-mark 2>/dev/null || iptables -t mangle -I PREROUTING -j CONNMARK --restore-mark")
    run("iptables -t mangle -C OUTPUT -j CONNMARK --restore-mark 2>/dev/null || iptables -t mangle -I OUTPUT -j CONNMARK --restore-mark")


def mark_connections(marks, prev, desired):
    if not marks:
        prev.clear()
        return
    try:
        payload = fetch_connections()
    except Exception as exc:
        log(f"读取 sing-box 连接失败: {exc}")
        return

    # 重用 desired dict，避免每轮分配与 GC
    desired.clear()
    for conn in payload.get("connections", []):
        if not isinstance(conn, dict):
            continue
        meta = conn.get("metadata") or {}
        account_no = str(meta.get("authUser") or "").strip()
        mark_id = marks.get(account_no)
        if not mark_id:
            continue
        src_ip = str(meta.get("sourceIP") or "").strip()
        src_port = str(meta.get("sourcePort") or "").strip()
        if not src_ip or not src_port.isdigit():
            continue
        proto = "tcp" if str(meta.get("network") or "").lower() == "tcp" else "udp"
        desired[(src_ip, src_port, proto)] = mark_id

    # 仅对新连接或 mark 变化的连接调用 conntrack，稳态下无子进程开销
    for (src_ip, src_port, proto), mark_id in desired.items():
        if prev.get((src_ip, src_port, proto)) == mark_id:
            continue
        r = subprocess.run(
            ["conntrack", "-U", "-p", proto, "--src", src_ip, "--sport", src_port, "--mark", str(mark_id)],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
        )
        if r.returncode != 0 and r.stderr and "0 flow entries" not in r.stderr:
            log(f"conntrack 打标失败: {src_ip}:{src_port}/{proto}: {r.stderr.strip()}")

    prev.clear()
    prev.update(desired)


def main():
    nic = None
    accounts_mtime = None
    marks = {}
    prev = {}
    desired = {}  # 复用，避免每轮分配

    while True:
        try:
            if nic is None:
                nic = detect_nic()

            try:
                mtime = os.path.getmtime(CONFIG_FILE)
            except OSError:
                mtime = None

            if mtime != accounts_mtime:
                accounts = load_accounts()
                accounts_mtime = mtime
                marks = rebuild_tc(nic, accounts)
                setup_iptables()
                prev.clear()

            mark_connections(marks, prev, desired)
        except Exception as exc:
            log(f"限速代理执行失败: {exc}")
            nic = None
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
