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
AIROPSCAT_RATELIMIT_ACTIVE_TTL=30
AIROPSCAT_RATELIMIT_MAX_ACTIVE_CLASSES=1024
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
ROOT_RATE = os.getenv("AIROPSCAT_RATELIMIT_ROOT_RATE", "10000mbit")
IPTS_CHAIN = "AIROPSCAT_MARK"
TC_CLASS_MINOR_MAX = 65534


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
ACTIVE_TTL = max(POLL_INTERVAL, env_int("AIROPSCAT_RATELIMIT_ACTIVE_TTL", 30, 1))
MAX_ACTIVE_CLASSES = env_int("AIROPSCAT_RATELIMIT_MAX_ACTIVE_CLASSES", 1024, 0, TC_CLASS_MINOR_MAX - 2)

# 持久 HTTP 连接，避免每轮重建 TCP 连接
_http_conn = None
_last_clip_log_at = 0


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
    burst = max(128k, 速率对应的 1 秒字节数)，单位 kB。"""
    rate_bytes_per_sec = speed_kbit * 1000 // 8
    burst_bytes = max(128 * 1024, rate_bytes_per_sec)
    return max(128, burst_bytes // 1024)


def rebuild_tc(nic, accounts):
    """删除并重建 HTB 规则，返回 account_no -> mark_id 映射。仅为近期活跃账号建立 class。"""
    run(f"tc qdisc del dev {nic} root 2>/dev/null || true")
    run(f"iptables -t mangle -F {IPTS_CHAIN} 2>/dev/null || true")
    if not accounts:
        return {}

    run(f"tc qdisc add dev {nic} root handle 1: htb default 9999 r2q 1")
    run(f"tc class add dev {nic} parent 1: classid 1:1 htb rate {ROOT_RATE} burst 1m cburst 1m quantum 1514")
    run(f"tc class add dev {nic} parent 1:1 classid 1:9999 htb rate {ROOT_RATE} burst 1m cburst 1m quantum 1514")

    marks = {}
    class_id = 2
    for account_no in sorted(accounts):
        if class_id == 9999:
            class_id += 1
        if class_id > TC_CLASS_MINOR_MAX:
            log(f"HTB class 数量达到 classid 上限，跳过剩余账号: built={len(marks)}, skipped={len(accounts) - len(marks)}")
            break
        speed_kbit = accounts[account_no] * 8
        burst_k = calc_burst(speed_kbit)
        class_result = run(f"tc class add dev {nic} parent 1:1 classid 1:{class_id} htb rate {speed_kbit}kbit burst {burst_k}k cburst {burst_k}k quantum 1514")
        if class_result.returncode != 0:
            log(f"tc class 添加失败: account={account_no}, classid=1:{class_id}, speed={speed_kbit}kbit: {class_result.stderr.strip()}")
            class_id += 1
            continue
        filter_result = run(f"tc filter add dev {nic} parent 1: handle {class_id} fw flowid 1:{class_id}")
        if filter_result.returncode != 0:
            run(f"tc class del dev {nic} classid 1:{class_id} 2>/dev/null || true")
            log(f"tc filter 添加失败: account={account_no}, classid=1:{class_id}: {filter_result.stderr.strip()}")
            class_id += 1
            continue
        marks[account_no] = class_id
        class_id += 1
    return marks


def extract_active_accounts(payload, accounts, last_seen):
    """从 sing-box 连接中提取活跃账号，并裁剪 HTB class 数量，避免事件队列过大。"""
    global _last_clip_log_at
    now = time.monotonic()
    current_counts = {}
    for conn in payload.get("connections", []):
        if not isinstance(conn, dict):
            continue
        meta = conn.get("metadata") or {}
        account_no = str(meta.get("authUser") or "").strip()
        if account_no in accounts:
            last_seen[account_no] = now
            current_counts[account_no] = current_counts.get(account_no, 0) + 1

    candidates = []
    for account_no, seen_at in list(last_seen.items()):
        if account_no not in accounts or now - seen_at > ACTIVE_TTL:
            last_seen.pop(account_no, None)
            continue
        candidates.append((account_no, seen_at, current_counts.get(account_no, 0)))

    if MAX_ACTIVE_CLASSES and len(candidates) > MAX_ACTIVE_CLASSES:
        candidates.sort(key=lambda item: (item[2] > 0, item[2], item[1], item[0]), reverse=True)
        selected = candidates[:MAX_ACTIVE_CLASSES]
        skipped = candidates[MAX_ACTIVE_CLASSES:]
        for account_no, _, count in skipped:
            if count == 0:
                last_seen.pop(account_no, None)
        if now - _last_clip_log_at >= 60:
            log(f"活跃账号超过 HTB class 上限，已裁剪: active={len(candidates)}, kept={len(selected)}, skipped={len(skipped)}")
            _last_clip_log_at = now
        candidates = selected

    active = {}
    for account_no, _, _ in candidates:
        active[account_no] = accounts[account_no]
    return active


def setup_iptables():
    # 创建专用链，用于 UDP 直接 MARK（QUIC/hysteria2 走 NOTRACK 没有 conntrack 条目）
    run(f"iptables -t mangle -N {IPTS_CHAIN} 2>/dev/null || true")
    # OUTPUT 链：先跳 AIROPSCAT_MARK（UDP 直接打标），再 CONNMARK restore（TCP 走 conntrack）
    run(f"iptables -t mangle -C OUTPUT -j {IPTS_CHAIN} 2>/dev/null || iptables -t mangle -I OUTPUT -j {IPTS_CHAIN}")
    run("iptables -t mangle -C OUTPUT -j CONNMARK --restore-mark 2>/dev/null || iptables -t mangle -A OUTPUT -j CONNMARK --restore-mark")
    run("iptables -t mangle -C PREROUTING -j CONNMARK --restore-mark 2>/dev/null || iptables -t mangle -I PREROUTING -j CONNMARK --restore-mark")


def sync_udp_rules(desired_udp, prev_udp):
    """同步 UDP 连接的 iptables MARK 规则。
    hysteria2/QUIC 走 NOTRACK，无 conntrack 条目，直接在 OUTPUT 链对回包打标：
    发给客户端的 UDP 包 dst=client_ip dport=client_port，命中规则后 TC fw filter 识别并限速。"""
    for (client_ip, client_port), mark_id in desired_udp.items():
        old_mark = prev_udp.get((client_ip, client_port))
        if old_mark == mark_id:
            continue
        if old_mark is not None:
            run(f"iptables -t mangle -D {IPTS_CHAIN} -p udp -d {client_ip} --dport {client_port} -j MARK --set-mark {old_mark} 2>/dev/null || true")
        r = run(f"iptables -t mangle -A {IPTS_CHAIN} -p udp -d {client_ip} --dport {client_port} -j MARK --set-mark {mark_id}")
        if r.returncode != 0:
            log(f"iptables 添加规则失败: {client_ip}:{client_port}/udp mark={mark_id}: {r.stderr.strip()}")

    for (client_ip, client_port), mark_id in list(prev_udp.items()):
        if (client_ip, client_port) not in desired_udp:
            run(f"iptables -t mangle -D {IPTS_CHAIN} -p udp -d {client_ip} --dport {client_port} -j MARK --set-mark {mark_id} 2>/dev/null || true")

    prev_udp.clear()
    prev_udp.update(desired_udp)


def mark_connections(payload, marks, prev_tcp, prev_udp, desired):
    if not marks:
        prev_tcp.clear()
        sync_udp_rules({}, prev_udp)
        return

    desired.clear()
    desired_udp = {}
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
        conn_type = str(meta.get("type") or "").lower()
        if "hysteria2" in conn_type:
            # QUIC/UDP：conntrack 不可用（NOTRACK），走 iptables OUTPUT 直接打标
            desired_udp[(src_ip, src_port)] = mark_id
        else:
            proto = "tcp" if str(meta.get("network") or "").lower() == "tcp" else "udp"
            desired[(src_ip, src_port, proto)] = mark_id

    # TCP：conntrack 打标，仅对新连接或 mark 变化的条目调用，成功才写入 prev
    new_prev_tcp = {}
    for (src_ip, src_port, proto), mark_id in desired.items():
        if prev_tcp.get((src_ip, src_port, proto)) == mark_id:
            new_prev_tcp[(src_ip, src_port, proto)] = mark_id
            continue
        r = subprocess.run(
            ["conntrack", "-U", "-p", proto, "--src", src_ip, "--sport", src_port, "--mark", str(mark_id)],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
        )
        if r.returncode == 0:
            new_prev_tcp[(src_ip, src_port, proto)] = mark_id
        elif r.returncode != 0 and r.stderr and "0 flow entries" not in r.stderr:
            log(f"conntrack 打标失败: {src_ip}:{src_port}/{proto}: {r.stderr.strip()}")
    prev_tcp.clear()
    prev_tcp.update(new_prev_tcp)

    # UDP（hysteria2）：同步 iptables MARK 规则
    sync_udp_rules(desired_udp, prev_udp)


def main():
    nic = None
    accounts_mtime = None
    marks = {}
    prev_tcp = {}
    prev_udp = {}
    desired = {}
    accounts = {}
    active_accounts = {}
    last_seen_accounts = {}

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
                last_seen_accounts = {account_no: seen_at for account_no, seen_at in last_seen_accounts.items() if account_no in accounts}
                active_accounts = {}
                marks = rebuild_tc(nic, {})
                prev_tcp.clear()
                prev_udp.clear()

            if not accounts:
                setup_iptables()
                time.sleep(POLL_INTERVAL)
                continue

            try:
                payload = fetch_connections()
            except Exception as exc:
                log(f"读取 sing-box 连接失败: {exc}")
                time.sleep(POLL_INTERVAL)
                continue

            next_active_accounts = extract_active_accounts(payload, accounts, last_seen_accounts)
            if next_active_accounts != active_accounts:
                active_accounts = next_active_accounts
                marks = rebuild_tc(nic, active_accounts)
                setup_iptables()
                prev_tcp.clear()
                prev_udp.clear()
                log(f"已刷新限速规则: activeAccounts={len(active_accounts)}, totalAccounts={len(accounts)}")

            mark_connections(payload, marks, prev_tcp, prev_udp, desired)
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
