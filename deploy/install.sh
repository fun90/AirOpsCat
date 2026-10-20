#!/usr/bin/env bash
# 安装正式 Go 资产；所有下载和校验均在停服前完成。
set +x
set -Eeuo pipefail
umask 077

fail() { printf '%s\n' "$*" >&2; exit 1; }
paths() {
    bin="$root/usr/local/bin/airopscat"
    config="$root/etc/airopscat"
    state="$root/var/lib/airopscat"
    unit="$root/etc/systemd/system/airopscat.service"
    db="$state/airopscat.db"
}
random() { openssl rand -hex 32; }
new_config() {
    printf 'AIROPSCAT_SECRET_KEY=%s\nAIROPSCAT_DB=/var/lib/airopscat/airopscat.db\nAIROPSCAT_LISTEN=127.0.0.1:8080\n' "$(random)" > "$1"
}
new_admin() {
    printf 'AIROPSCAT_ADMIN_EMAIL=admin@airopscat.local\nAIROPSCAT_ADMIN_PASSWORD=%s\n' "$(random)" > "$1"
}
validate_config() {
    # 不 source 配置；systemd EnvironmentFile 不执行 shell 代码。
    awk '
      /^[[:space:]]*($|#)/ { next }
      $0 !~ /^[A-Z_][A-Z0-9_]*=[A-Za-z0-9_\/.:,@+=%-]*$/ { bad=1 }
      /^AIROPSCAT_SECRET_KEY=/ { secret++; if ($0 !~ /^AIROPSCAT_SECRET_KEY=[A-Za-z0-9+\/=_-]{32,}$/) bad=1 }
      /^AIROPSCAT_DB=/ { db++; if ($0 != "AIROPSCAT_DB=/var/lib/airopscat/airopscat.db") bad=1 }
      /^AIROPSCAT_LISTEN=/ { listen++; if ($0 != "AIROPSCAT_LISTEN=127.0.0.1:8080") bad=1 }
      /^[[:space:]]*(export[[:space:]]+)?AIROPSCAT_(SECRET_KEY|DB|LISTEN)[[:space:]]*=/ {
        if ($0 !~ /^AIROPSCAT_(SECRET_KEY|DB|LISTEN)=/) bad=1
      }
      END { exit !(secret == 1 && db <= 1 && listen <= 1 && !bad) }
    ' "$1" || fail '配置必须含唯一的至少32字符 secret，并使用固定 DB/listen；请按部署文档规范配置'
}
verify_asset() {
    local name=$1 hash
    hash=$(awk -v name="$name" '$2 == name { print $1 }' "$stage/checksums.txt")
    [[ "$hash" =~ ^[0-9a-f]{64}$ ]] || fail "checksum 缺失、重复或格式错误：$name"
    local actual
    actual=$(sha256sum "$stage/$name") || fail "无法计算 checksum：$name"
    [[ ${actual%% *} == "$hash" ]] || fail "checksum 不匹配：$name"
}
backup_files() {
    local path
    for path in "$bin" "$unit" "$config/airopscat.env" "$config/initial-admin.env" "$db" "$db-wal" "$db-shm"; do
        if [[ -e "$path" ]]; then
            [[ -f "$path" && ! -L "$path" ]] || fail "拒绝非普通文件：$path"
            cp -p -- "$path" "$backup/$(basename "$path")" || return 1
        fi
    done
    printf '%s\n' "${was_active:-0}" > "$backup/was-active"
    printf '%s\n' "${was_enabled:-0}" > "$backup/was-enabled"
    touch "$backup/complete"
}
restore_files() {
    local path saved
    [[ -f "$backup/complete" ]] || return 1
    for path in "$bin" "$unit" "$config/airopscat.env" "$config/initial-admin.env" "$db" "$db-wal" "$db-shm"; do
        saved="$backup/$(basename "$path")"
        if [[ -f "$saved" ]]; then
            # 二进制通过同目录 rename 恢复；数据库在停服状态下整组恢复。
            cp -p -- "$saved" "$path.restore" || return 1
            mv -f -- "$path.restore" "$path" || return 1
        else
            rm -f -- "$path" || return 1
        fi
    done
    for path in "$db" "$db-wal" "$db-shm"; do
        [[ ! -f "$path" ]] || chown airopscat:airopscat "$path" || return 1
    done
}
cleanup() {
    local rc=$? restored=1
    trap - EXIT INT TERM
    set +e
    if (( rc != 0 && transaction )); then
        printf '%s\n' '安装失败，停止新版并恢复二进制、配置及同组数据库文件' >&2
        if [[ $(systemctl show airopscat.service --property LoadState --value) == not-found ]] || systemctl stop airopscat.service; then
            if (( ! was_enabled )); then systemctl disable airopscat.service >/dev/null 2>&1 || restored=0; fi
            restore_files || restored=0
            systemctl daemon-reload || restored=0
            if (( restored && was_active )); then systemctl start airopscat.service || restored=0; fi
        else
            restored=0
        fi
        if (( ! restored )); then
            printf '自动恢复未完成，请保持服务停止并按文档恢复：%s\n' "$backup" >&2
        fi
    elif (( rc != 0 && stopped && was_active )); then
        systemctl start airopscat.service || printf '%s\n' '旧服务重新启动失败，请人工检查' >&2
    fi
    [[ -z "${stage:-}" ]] || rm -rf -- "$stage"
    rm -f -- "$bin.new"
    exit "$rc"
}
healthy() {
    local pid executable
    systemctl is-active --quiet airopscat.service || return 1
    pid=$(systemctl show airopscat.service --property MainPID --value)
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
    executable=$(readlink "/proc/$pid/exe") || return 1
    [[ "$executable" == "$bin" ]] || return 1
    ss -H -ltnp 'sport = :8080' | grep -F "pid=$pid," >/dev/null || return 1
    curl --fail --silent --max-time 2 http://127.0.0.1:8080/healthz >/dev/null
}
self_test() (
    # 测试只使用临时目录及 mock 命令，不下载、不触碰宿主 systemd。
    root=$(mktemp -d)
    trap 'rm -rf -- "$root"' EXIT
    paths
    mkdir -p "$(dirname "$bin")" "$(dirname "$unit")" "$config" "$state"
    new_config "$config/airopscat.env"
    new_admin "$config/initial-admin.env"
    validate_config "$config/airopscat.env"
    [[ $(LC_ALL=C ls -l "$config/airopscat.env" | awk '{print $1}') == -rw------- ]]
    [[ $(LC_ALL=C ls -l "$config/initial-admin.env" | awk '{print $1}') == -rw------- ]]
    cp "$config/initial-admin.env" "$root/admin.before"
    [[ -s "$config/initial-admin.env" ]] || new_admin "$config/initial-admin.env"
    cmp "$root/admin.before" "$config/initial-admin.env"
    chown() { :; }
    systemctl() {
        if [[ $1 == show ]]; then printf 'loaded\n'; else printf '%s\n' "$*" >> "$root/systemctl.calls"; fi
    }
    printf old-bin > "$bin"
    printf old-unit > "$unit"
    printf old-db > "$db"
    printf old-wal > "$db-wal"
    backup="$root/backup"
    mkdir "$backup"
    backup_files
    # 备份失败时事务尚未开始，只重启原服务，不能恢复部分备份。
    (
        transaction=0 stopped=1 was_active=1 was_enabled=1 stage=''
        trap cleanup EXIT
        cp() { return 1; }
        backup_files || exit 1
    ) && exit 1
    cmp "$bin" "$backup/airopscat"
    cmp "$db" "$backup/airopscat.db"
    : > "$root/systemctl.calls"
    # 模拟新版已迁移 schema 并写入 WAL/SHM，随后启动失败。
    printf new-bin > "$bin"
    printf new-schema > "$db"
    printf new-wal > "$db-wal"
    printf new-shm > "$db-shm"
    (
        transaction=1 stopped=1 was_active=1 was_enabled=1 stage=''
        trap cleanup EXIT
        exit 1
    ) && exit 1
    cmp "$bin" "$backup/airopscat"
    cmp "$db" "$backup/airopscat.db"
    cmp "$db-wal" "$backup/airopscat.db-wal"
    [[ ! -e "$db-shm" ]]
    [[ $(head -n 1 "$root/systemctl.calls") == 'stop airopscat.service' ]]
    [[ $(tail -n 1 "$root/systemctl.calls") == 'start airopscat.service' ]]
    # 旧组没有 WAL/SHM 时，必须移除新版留下的两个文件。
    rm "$backup/airopscat.db-wal"
    printf new-shm > "$db-shm"
    restore_files
    [[ ! -e "$db-wal" && ! -e "$db-shm" ]]
    # 首装失败：旧文件全部不存在，回滚后允许重新生成凭据。
    rm -f "$bin" "$unit" "$config/airopscat.env" "$config/initial-admin.env" "$db"
    backup="$root/first-install-backup"
    mkdir "$backup"
    backup_files
    new_config "$config/airopscat.env"
    new_admin "$config/initial-admin.env"
    printf new-bin > "$bin"
    printf new-unit > "$unit"
    printf new-schema > "$db"
    printf new-wal > "$db-wal"
    printf new-shm > "$db-shm"
    (
        transaction=1 stopped=1 was_active=0 was_enabled=0 stage=''
        trap cleanup EXIT
        exit 1
    ) && exit 1
    for path in "$bin" "$unit" "$config/airopscat.env" "$config/initial-admin.env" "$db" "$db-wal" "$db-shm"; do
        [[ ! -e "$path" ]]
    done
    new_config "$config/airopscat.env"
    new_admin "$config/initial-admin.env"
    validate_config "$config/airopscat.env"
    stage="$root/download"
    mkdir "$stage"
    printf asset > "$stage/asset"
    (cd "$stage" && sha256sum asset > checksums.txt)
    verify_asset asset
    cp "$stage/checksums.txt" "$stage/good-checksums"
    printf corrupted > "$stage/asset"
    if (verify_asset asset) 2>/dev/null; then exit 1; fi
    printf asset > "$stage/asset"
    : > "$stage/checksums.txt"
    if (verify_asset asset) 2>/dev/null; then exit 1; fi
    cat "$stage/good-checksums" "$stage/good-checksums" > "$stage/checksums.txt"
    if (verify_asset asset) 2>/dev/null; then exit 1; fi
    printf '\nAIROPSCAT_LISTEN=0.0.0.0:8080\n' >> "$config/airopscat.env"
    if (validate_config "$config/airopscat.env") 2>/dev/null; then exit 1; fi
    printf '%s\n' '安装器自检查通过：首次配置、密码保留、0600、失败回滚、WAL/SHM 缺失恢复、checksum 拒绝'
)

if [[ ${1:-} == --self-test && $# == 1 ]]; then
    self_test
    exit
fi
[[ $EUID == 0 ]] || fail '必须以 root 运行'
[[ $# == 1 && $1 =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail '用法：bash install.sh v1.2.3（或 1.2.3）'
version="v${1#v}"
[[ $(uname -s) == Linux ]] || fail '仅支持 Linux systemd 主机'
case $(uname -m) in
    x86_64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) fail '仅支持 amd64/arm64' ;;
esac
for command in curl sha256sum tar openssl systemctl flock getent useradd groupadd install awk readlink grep id cut ss; do
    command -v "$command" >/dev/null || fail "缺少命令：$command"
done
root=''
paths
mkdir -p /run/airopscat
exec 9>/run/airopscat/install.lock
flock -n 9 || fail '已有安装任务运行'
transaction=0 stopped=0 was_active=0 was_enabled=0 stage=''
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
stage=$(mktemp -d /var/tmp/airopscat-install.XXXXXXXX)
base="https://github.com/fun90/AirOpsCat/releases/download/$version"
archive="airopscat-linux-$arch.tar.gz"
for asset in checksums.txt "$archive" airopscat.service; do
    curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
        --connect-timeout 15 --max-time 300 --output "$stage/$asset" "$base/$asset"
done
verify_asset "$archive"
verify_asset airopscat.service
binary="airopscat-linux-$arch"
[[ $(tar -tzf "$stage/$archive") == "$binary" ]] || fail '归档必须仅包含对应架构的二进制'
[[ $(tar -tvzf "$stage/$archive") == -* ]] || fail '归档成员必须是普通文件'
tar -xzf "$stage/$archive" --no-same-owner --no-same-permissions -C "$stage" "$binary"
[[ -f "$stage/$binary" && ! -L "$stage/$binary" && -s "$stage/$binary" ]] || fail '无效二进制'

for path in "$config" "$state" "$bin" "$unit" "$config/airopscat.env" "$config/initial-admin.env" "$db" "$db-wal" "$db-shm"; do
    [[ ! -L "$path" ]] || fail "拒绝符号链接：$path"
done
if [[ -f "$config/airopscat.env" ]]; then
    validate_config "$config/airopscat.env"
else
    [[ ! -e "$db" ]] || fail '已有数据库但缺少 secret 配置，拒绝生成新密钥'
    new_config "$stage/airopscat.env"
fi
if [[ ! -e "$config/initial-admin.env" ]]; then
    new_admin "$stage/initial-admin.env"
elif [[ -s "$config/initial-admin.env" ]]; then
    awk 'NR == 1 && $0 != "AIROPSCAT_ADMIN_EMAIL=admin@airopscat.local" { exit 1 }
         NR == 2 && $0 !~ /^AIROPSCAT_ADMIN_PASSWORD=[A-Za-z0-9_-]{32,}$/ { exit 1 }
         END { if (NR != 2) exit 1 }' "$config/initial-admin.env" || fail '首次管理员密码文件格式无效'
else
    [[ -s "$db" ]] || fail '空数据库需要非空的首次管理员密码文件'
fi
getent group airopscat >/dev/null || groupadd --system airopscat
if ! getent passwd airopscat >/dev/null; then
    useradd --system --gid airopscat --home-dir /var/lib/airopscat --no-create-home --shell /usr/sbin/nologin airopscat
fi
[[ $(id -u airopscat) != 0 && $(id -g airopscat) == "$(getent group airopscat | cut -d: -f3)" ]] || fail 'airopscat 必须使用独立非 root 用户和组'
install -d -o root -g root -m 0700 "$config" /var/backups/airopscat
install -d -o airopscat -g airopscat -m 0700 "$state"
install -d -o root -g root -m 0755 "$(dirname "$bin")" "$(dirname "$unit")"
systemctl is-active --quiet airopscat.service && was_active=1
systemctl is-enabled --quiet airopscat.service && was_enabled=1
# 停服后复制完整文件组；不对运行中的 WAL 做裸拷贝，也不执行 VACUUM。
if [[ $(systemctl show airopscat.service --property LoadState --value) != not-found ]]; then
    systemctl stop airopscat.service
fi
stopped=1
backup=$(mktemp -d "/var/backups/airopscat/${version}.XXXXXXXX")
backup_files
transaction=1
if [[ -f "$stage/airopscat.env" ]]; then
    install -o root -g root -m 0600 "$stage/airopscat.env" "$config/airopscat.env"
fi
if [[ -f "$stage/initial-admin.env" ]]; then
    install -o root -g root -m 0600 "$stage/initial-admin.env" "$config/initial-admin.env"
fi
chown root:root "$config/airopscat.env" "$config/initial-admin.env"
chmod 0600 "$config/airopscat.env" "$config/initial-admin.env"
for path in "$db" "$db-wal" "$db-shm"; do
    if [[ -f "$path" ]]; then chown airopscat:airopscat "$path"; chmod 0600 "$path"; fi
done
install -o root -g root -m 0755 "$stage/$binary" "$bin.new"
mv -f -- "$bin.new" "$bin"
install -o root -g root -m 0644 "$stage/airopscat.service" "$unit"
systemctl daemon-reload
systemctl start airopscat.service
ready=0
for ((attempt=0; attempt<30; attempt++)); do
    if healthy; then ready=1; break; fi
    sleep 1
done
(( ready )) || fail '新版本未通过健康检查'
systemctl enable airopscat.service >/dev/null
transaction=0
printf '已安装 %s，监听 127.0.0.1:8080；首次凭据：%s（仅 root 可读）；升级前备份：%s\n' \
    "$version" "$config/initial-admin.env" "$backup"
