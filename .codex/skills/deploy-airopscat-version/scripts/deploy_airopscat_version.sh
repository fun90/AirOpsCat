#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
HOST="${AIROPSCAT_DEPLOY_HOST:-root@ssh.fun90.com}"
APP_DIR="${AIROPSCAT_DEPLOY_DIR:-/data/airopscat}"
SERVICE="${AIROPSCAT_DEPLOY_SERVICE:-airopscat}"

if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version>" >&2
  exit 64
fi

if [[ ! "$VERSION" =~ ^[0-9]+([.][0-9]+){1,3}([-+][A-Za-z0-9._-]+)?$ ]]; then
  echo "Invalid version: $VERSION" >&2
  exit 64
fi

echo "== SSH probe: $HOST =="
ssh -o BatchMode=yes -o ConnectTimeout=10 "$HOST" 'echo SSH_OK && hostname && pwd'

echo "== Deploy AirOpsCat $VERSION =="
ssh "$HOST" "cd '$APP_DIR' && ./install.sh '$VERSION'"

echo "== Verify service: $SERVICE =="
ssh "$HOST" "
set -e
echo 'service_active='\"\$(systemctl is-active '$SERVICE' 2>/dev/null || true)\"
systemctl --no-pager --full status '$SERVICE' 2>/dev/null | sed -n '1,35p' || true
echo '== files and process =='
cd '$APP_DIR'
ls -lh airopscat* 2>/dev/null || true
ps -ef | grep -i '[a]iropscat' || true
"
