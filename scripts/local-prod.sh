#!/usr/bin/env bash
# 用生产库只读快照在本机启动调试实例；关闭后台任务、通知、SSH 与 DNS 外发。
# 用法：scripts/local-prod.sh [--no-pull]；需 AIROPSCAT_DEPLOY_HOST 与本机 secret 文件。
set -euo pipefail
host="${AIROPSCAT_DEPLOY_HOST:-}"
dir="${AIROPSCAT_LOCAL_DIR:-$HOME/.airopscat-local}"
secret_file="${AIROPSCAT_LOCAL_SECRET_FILE:-$dir/secret}"
listen="${AIROPSCAT_LISTEN:-127.0.0.1:8080}"
remote_db=/var/lib/airopscat/airopscat.db
remote_env=/etc/airopscat/airopscat.env
ssh_opts=(-o BatchMode=yes -o ConnectTimeout=10)

mkdir -p "$dir" && chmod 700 "$dir"
[[ -r "$secret_file" ]] || { echo "缺少 secret 文件：$secret_file（与生产 AIROPSCAT_SECRET_KEY 相同，权限 600）" >&2; exit 64; }
secret=$(tr -d '\n' < "$secret_file")

if [[ "${1:-}" != --no-pull ]]; then
	[[ -n "$host" && "$host" != -* ]] || { echo '请配置 AIROPSCAT_DEPLOY_HOST' >&2; exit 64; }
	# sqlite 在线备份：只读打开，不停服、不裸拷 WAL
	ssh "${ssh_opts[@]}" "$host" "set -e; t=\$(mktemp -d); trap 'rm -rf \$t' EXIT; sqlite3 -readonly $remote_db \".backup \$t/a.db\"; gzip -c \$t/a.db" > "$dir/prod.db.gz.tmp"
	rm -f "$dir"/prod.db{,-wal,-shm}
	gunzip -c "$dir/prod.db.gz.tmp" > "$dir/prod.db" && rm -f "$dir/prod.db.gz.tmp"
	chmod 600 "$dir/prod.db"
	[[ $(sqlite3 "$dir/prod.db" 'PRAGMA integrity_check') == ok ]] || { echo '快照完整性检查失败' >&2; exit 1; }
	# 只比较哈希，不传输或打印 secret
	remote_hash=$(ssh "${ssh_opts[@]}" "$host" "grep '^AIROPSCAT_SECRET_KEY=' $remote_env | cut -d= -f2- | tr -d '\n' | sha256sum | cut -c1-64")
	local_hash=$(printf %s "$secret" | shasum -a 256 | cut -c1-64)
	[[ "$remote_hash" == "$local_hash" ]] || { echo '本机 secret 与生产不一致，加密字段将无法解密' >&2; exit 1; }
fi
[[ -f "$dir/prod.db" ]] || { echo "缺少 $dir/prod.db，去掉 --no-pull 重新拉取" >&2; exit 1; }

# 端口被上一轮 go run 的本地实例占用时替换它；其他进程占用则停止
port=${listen##*:}
for pid in $(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null); do
	cmd=$(ps -o comm= -p "$pid")
	[[ "$cmd" == */go-build*/airopscat ]] || { echo "端口 $port 被其他进程占用：$cmd" >&2; exit 1; }
	kill "$pid"; while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
	echo "已停止旧的本地实例 $pid"
done

cd "$(dirname "$0")/.."
echo "启动 http://$listen （数据库 $dir/prod.db，外发已禁用）"
AIROPSCAT_SECRET_KEY="$secret" AIROPSCAT_DB="$dir/prod.db" AIROPSCAT_LISTEN="$listen" \
	AIROPSCAT_BACKUP_DIR="$dir/backup" AIROPSCAT_TASKS_DISABLED=1 AIROPSCAT_OUTBOUND_DISABLED=1 \
	exec go run ./cmd/airopscat
