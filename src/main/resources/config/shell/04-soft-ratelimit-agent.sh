#!/usr/bin/env bash
# @title: 安装用户态限速治理器
# @description: 安装 airopscat-soft-ratelimit systemd 服务，通过 sing-box Clash API 按账号做平均速率控制，不使用 tc/HTB/nftables

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

  log "安装用户态限速治理器依赖: python3"
  apt-get install -y python3
}

install_agent_files() {
  log "创建限速配置目录"
  mkdir -p /usr/local/bin /etc/airopscat/ratelimit

  if [[ ! -f /etc/airopscat/ratelimit/accounts.json ]]; then
    cat > /etc/airopscat/ratelimit/accounts.json <<'EOF'
{"accounts":[]}
EOF
  fi

  cat > /etc/default/airopscat-soft-ratelimit <<'EOF'
# 通过 sing-box Clash API 进行用户态限速控制，不使用 tc/HTB/nftables
AIROPSCAT_SOFT_RATELIMIT_CONFIG=/etc/airopscat/ratelimit/accounts.json
AIROPSCAT_SOFT_RATELIMIT_CLASH_HOST=127.0.0.1
AIROPSCAT_SOFT_RATELIMIT_CLASH_PORT=19191
AIROPSCAT_SOFT_RATELIMIT_CLASH_SECRET=

# 采样周期越短，控制越及时；建议 2-5 秒
AIROPSCAT_SOFT_RATELIMIT_INTERVAL=3

# 计速字段：download 表示服务端下行到客户端；total 表示 upload+download
AIROPSCAT_SOFT_RATELIMIT_BYTE_MODE=download

# 允许短时突发的秒数；账号可突发到 speed * BURST_SECONDS
AIROPSCAT_SOFT_RATELIMIT_BURST_SECONDS=20

# 同一账号连续处置的最小间隔，避免频繁断开
AIROPSCAT_SOFT_RATELIMIT_COOLDOWN_SECONDS=10

# 单轮最多关闭多少条连接
AIROPSCAT_SOFT_RATELIMIT_CLOSE_BATCH=4

# 新建连接至少存活多少秒后才参与关闭，避免刚认证就被断
AIROPSCAT_SOFT_RATELIMIT_MIN_CONNECTION_AGE=8
EOF

  cat > /usr/local/bin/airopscat-soft-ratelimit-agent <<'EOF'
#!/usr/bin/env python3
import http.client
import json
import os
import sys
import time
import urllib.parse


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


CONFIG_FILE = os.getenv("AIROPSCAT_SOFT_RATELIMIT_CONFIG", "/etc/airopscat/ratelimit/accounts.json")
CLASH_HOST = os.getenv("AIROPSCAT_SOFT_RATELIMIT_CLASH_HOST", "127.0.0.1")
CLASH_PORT = env_int("AIROPSCAT_SOFT_RATELIMIT_CLASH_PORT", 19191, 1, 65535)
CLASH_SECRET = os.getenv("AIROPSCAT_SOFT_RATELIMIT_CLASH_SECRET", "").strip()
POLL_INTERVAL = env_int("AIROPSCAT_SOFT_RATELIMIT_INTERVAL", 3, 1)
BYTE_MODE = os.getenv("AIROPSCAT_SOFT_RATELIMIT_BYTE_MODE", "download").strip().lower()
BURST_SECONDS = env_int("AIROPSCAT_SOFT_RATELIMIT_BURST_SECONDS", 20, 1)
COOLDOWN_SECONDS = env_int("AIROPSCAT_SOFT_RATELIMIT_COOLDOWN_SECONDS", 10, 1)
CLOSE_BATCH = env_int("AIROPSCAT_SOFT_RATELIMIT_CLOSE_BATCH", 4, 1, 100)
MIN_CONNECTION_AGE = env_int("AIROPSCAT_SOFT_RATELIMIT_MIN_CONNECTION_AGE", 8, 0)

_last_error_log_at = {}
_http_conn = None


def log(msg):
    print(time.strftime("[%Y-%m-%d %H:%M:%S]"), msg, flush=True)


def log_limited(key, msg, interval=60):
    now = time.monotonic()
    if now - _last_error_log_at.get(key, 0) >= interval:
        _last_error_log_at[key] = now
        log(msg)


def request(method, path):
    global _http_conn
    headers = {"Accept": "application/json"}
    if CLASH_SECRET:
        headers["Authorization"] = "Bearer " + CLASH_SECRET

    for attempt in range(2):
        try:
            if _http_conn is None:
                _http_conn = http.client.HTTPConnection(CLASH_HOST, CLASH_PORT, timeout=5)
            _http_conn.request(method, path, headers=headers)
            resp = _http_conn.getresponse()
            try:
                body = resp.read()
                if resp.status < 200 or resp.status >= 300:
                    raise RuntimeError(f"Clash API status={resp.status}, path={path}, body={body[:200]!r}")
                if not body:
                    return None
                return json.loads(body.decode("utf-8"))
            finally:
                resp.close()
        except Exception:
            try:
                if _http_conn is not None:
                    _http_conn.close()
            except Exception:
                pass
            _http_conn = None
            if attempt == 1:
                raise


def fetch_connections():
    payload = request("GET", "/connections")
    connections = payload.get("connections", []) if isinstance(payload, dict) else []
    return connections if isinstance(connections, list) else []


def close_connection(connection_id):
    encoded = urllib.parse.quote(str(connection_id), safe="")
    request("DELETE", "/connections/" + encoded)


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
            # 兼容旧限速配置语义：speed 表示 KB/s，换算为 bytes/s。
            accounts[account_no] = speed * 1000
    return accounts


def account_of(conn):
    meta = conn.get("metadata") if isinstance(conn, dict) else None
    if not isinstance(meta, dict):
        return ""
    return str(meta.get("authUser") or "").strip()


def byte_value(conn):
    try:
        upload = int(conn.get("upload") or 0)
        download = int(conn.get("download") or 0)
    except (AttributeError, TypeError, ValueError):
        return 0
    if BYTE_MODE == "upload":
        return upload
    if BYTE_MODE == "total":
        return upload + download
    return download


def choose_connections_to_close(connections, account_no, first_seen, now):
    candidates = []
    for conn in connections:
        if account_of(conn) != account_no:
            continue
        conn_id = conn.get("id")
        if not conn_id:
            continue
        age = now - first_seen.get(conn_id, now)
        if age < MIN_CONNECTION_AGE:
            continue
        candidates.append((byte_value(conn), str(conn_id)))
    candidates.sort(reverse=True)
    return [conn_id for _, conn_id in candidates[:CLOSE_BATCH]]


def prune_connection_state(prev_bytes, first_seen, active_ids):
    for state in (prev_bytes, first_seen):
        for conn_id in list(state):
            if conn_id not in active_ids:
                del state[conn_id]


def main():
    accounts_signature = None
    accounts = {}
    tokens = {}
    last_action_at = {}
    prev_bytes = {}
    first_seen = {}
    last_tick = time.monotonic()

    log("用户态限速治理器已启动: mode=%s, interval=%ss" % (BYTE_MODE, POLL_INTERVAL))

    while True:
        try:
            now = time.monotonic()
            elapsed = max(now - last_tick, 0.001)
            last_tick = now

            try:
                stat = os.stat(CONFIG_FILE)
                signature = (stat.st_mtime_ns, stat.st_size)
            except OSError:
                signature = None
            if signature != accounts_signature:
                accounts = load_accounts()
                accounts_signature = signature
                tokens = {account_no: min(tokens.get(account_no, limit * BURST_SECONDS), limit * BURST_SECONDS)
                          for account_no, limit in accounts.items()}
                last_action_at = {account_no: last_action_at.get(account_no, 0) for account_no in accounts}
                log(f"已加载用户态限速账号: totalAccounts={len(accounts)}")

            connections = fetch_connections()
            active_ids = set()
            account_delta = {account_no: 0 for account_no in accounts}

            for conn in connections:
                conn_id = conn.get("id") if isinstance(conn, dict) else None
                if not conn_id:
                    continue
                conn_id = str(conn_id)
                active_ids.add(conn_id)
                first_seen.setdefault(conn_id, now)

                account_no = account_of(conn)
                current_bytes = byte_value(conn)
                previous_bytes = prev_bytes.get(conn_id)
                prev_bytes[conn_id] = current_bytes
                if account_no not in accounts or previous_bytes is None:
                    continue
                delta = current_bytes - previous_bytes
                if delta > 0:
                    account_delta[account_no] += delta

            prune_connection_state(prev_bytes, first_seen, active_ids)

            for account_no, limit in accounts.items():
                burst = limit * BURST_SECONDS
                tokens[account_no] = min(burst, tokens.get(account_no, burst) + limit * elapsed - account_delta.get(account_no, 0))
                if tokens[account_no] >= 0:
                    continue
                if now - last_action_at.get(account_no, 0) < COOLDOWN_SECONDS:
                    continue

                closed = []
                for conn_id in choose_connections_to_close(connections, account_no, first_seen, now):
                    try:
                        close_connection(conn_id)
                        closed.append(conn_id)
                    except Exception as exc:
                        log_limited(f"close_{conn_id}", f"关闭超速连接失败: account={account_no}, id={conn_id}, error={exc}", 30)

                if closed:
                    deficit = int(-tokens[account_no])
                    last_action_at[account_no] = now
                    log(f"账号超速，已关闭连接: account={account_no}, closed={len(closed)}, deficitBytes={deficit}, limitBytesPerSecond={limit}")
        except Exception as exc:
            log_limited("main", f"用户态限速治理器执行失败: {exc}", 10)

        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
EOF

  chmod 0755 /usr/local/bin/airopscat-soft-ratelimit-agent

  cat > /etc/systemd/system/airopscat-soft-ratelimit.service <<'EOF'
[Unit]
Description=AirOpsCat Soft Rate Limit Agent
After=network-online.target sing-box.service
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=-/etc/default/airopscat-soft-ratelimit
ExecStart=/usr/local/bin/airopscat-soft-ratelimit-agent
Restart=always
RestartSec=2
Nice=10

[Install]
WantedBy=multi-user.target
EOF
}

enable_service() {
  log "启用并启动 airopscat-soft-ratelimit 服务"
  systemctl daemon-reload
  systemctl enable --now airopscat-soft-ratelimit.service
  systemctl restart airopscat-soft-ratelimit.service
}

main() {
  require_root
  install_packages
  install_agent_files
  enable_service
  log "用户态限速治理器安装完成"
}

main "$@"
