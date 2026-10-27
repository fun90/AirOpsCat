#!/usr/bin/env bash
set -euo pipefail
version="${1:-}"
host="${AIROPSCAT_DEPLOY_HOST:-}"
[[ "$version" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo '用法：deploy_airopscat_version.sh vX.Y.Z' >&2; exit 64; }
[[ -n "$host" && "$host" != -* ]] || { echo '请配置 AIROPSCAT_DEPLOY_HOST' >&2; exit 64; }
# 执行检出的 Go 安装器；生产主机必须已具备 root 权限或由用户预先授权 sudo。
base=$(cd "$(dirname "$0")/../../../.." && pwd)
ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" 'id -u; hostname'
ssh "$host" "bash -s -- '$version'" < "$base/deploy/install.sh"
ssh "$host" 'systemctl is-active airopscat.service; curl --fail --silent http://127.0.0.1:8080/healthz'
# 核对运行版本：取最近一次启动日志的 version=，与目标一致才算成功
want="v${version#v}"
got=$(ssh "$host" "journalctl -u airopscat.service --since '-10min' --no-pager -o cat | grep -o 'AirOpsCat 已启动.*version=[^ ]*' | tail -1 | sed 's/.*version=//'")
[[ "$got" == "$want" ]] || { echo "运行版本 ${got:-未知}，期望 $want" >&2; exit 1; }
echo "运行版本 $got"
