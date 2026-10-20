# AirOpsCat 服务器配置

## Go 正式安装与发布

正式资产仓库为 `fun90/AirOpsCat`。需要 Linux、systemd、root，以及 Bash、curl、OpenSSL、tar、sha256sum、flock、iproute2（`ss`）、shadow-utils（`useradd`/`groupadd`）和常规 GNU 工具。安装器仅接受完整版本 `v1.2.3` 或 `1.2.3`，不接受 `latest`、预发布后缀或其他下载地址。

发布资产：

| 文件 | 内容 |
| --- | --- |
| `airopscat-linux-amd64.tar.gz` | 唯一普通文件 `airopscat-linux-amd64` |
| `airopscat-linux-arm64.tar.gz` | 唯一普通文件 `airopscat-linux-arm64` |
| `airopscat.service` | 与本版本对应的 systemd 单元 |
| `install.sh` | 安装器 |
| `checksums.txt` | 上述四个文件的 SHA-256 |

两个二进制均使用 `CGO_ENABLED=0`、`-tags timetzdata` 构建，内嵌时区数据。CI 使用 `go.mod` 中的 Go 版本执行 `go vet ./...`、`go test ./...` 和安装器自检查。发布 workflow 仅允许 `v*` tag push，进一步要求 tag 精确匹配 `vX.Y.Z`，且 tag commit 是远端 `release/go` 分支的祖先；只有 `fun90/AirOpsCat` 的发布 job 获得 `contents: write`。`workflow_dispatch` 只构建和上传 Actions artifacts，不创建 Release。

### 首次安装

先从目标 Release 下载并检查安装器，不使用管道直接执行网络脚本。以下命令需 root，示例版本必须换为实际已发布的版本：

```bash
umask 077
version=v1.2.3
work=$(mktemp -d)
curl -fSL --proto '=https' --proto-redir '=https' \
    "https://github.com/fun90/AirOpsCat/releases/download/$version/install.sh" -o "$work/install.sh"
curl -fSL --proto '=https' --proto-redir '=https' \
    "https://github.com/fun90/AirOpsCat/releases/download/$version/checksums.txt" -o "$work/checksums.txt"
(cd "$work" && grep '  install.sh$' checksums.txt | sha256sum -c -)
bash "$work/install.sh" "$version"
rm -rf -- "$work"
```

安装器下载归档、单元和清单到私有暂存目录，校验 SHA-256 和归档成员后才停服。SHA-256 校验依赖 GitHub HTTPS 与同一 Release 清单的可信性，不是独立签名。

| 路径 | 所有者与权限 | 用途 |
| --- | --- | --- |
| `/usr/local/bin/airopscat` | `root:root 0755` | 原子替换的二进制 |
| `/etc/airopscat` | `root:root 0700` | 配置目录 |
| `/etc/airopscat/airopscat.env` | `root:root 0600` | secret、数据库路径与监听地址 |
| `/etc/airopscat/initial-admin.env` | `root:root 0600` | 一次生成的初始管理员凭据 |
| `/var/lib/airopscat` | `airopscat:airopscat 0700` | SQLite 与应用持久状态 |
| `/var/backups/airopscat/<version>.<随机后缀>/` | `root:root 0700` | 每次安装前的文件组备份 |

服务使用独立的非 root 用户和组 `airopscat`。systemd 由管理器读取 root 配置后传递环境；应用进程无需读取 `/etc/airopscat`。主配置格式为无引号的 `KEY=value`：

```ini
AIROPSCAT_SECRET_KEY=<安装器生成的64位十六进制随机串>
AIROPSCAT_DB=/var/lib/airopscat/airopscat.db
AIROPSCAT_LISTEN=127.0.0.1:8080
```

secret 仅在缺少配置且没有已有数据库时生成；已有库缺少 secret 将中止安装，以免失去解密能力。升级保留 secret 与管理员凭据，不输出密码或 secret，不执行环境文件内容。安装器拒绝其他 DB 或监听路径，避免备份错误的数据库。配置不得含续行、重复配置或 shell 语法。

首次管理员邮箱为 `admin@airopscat.local`，密码在 `initial-admin.env`，只在空库创建管理员时生效。请在 root 终端私下读取，勿将其复制到日志；首次登录立即改密。升级不会再次生成或重置密码。确认管理员已创建后，可用 `truncate -s 0 /etc/airopscat/initial-admin.env` 清除初始凭据；保留空文件供 systemd 读取，安装器在已有数据库时保留它。请勿对空数据库清空该文件，否则应用会自行生成密码并写日志。

默认仅监听 `127.0.0.1:8080`，通过本机反向代理或 SSH 隧道访问。服务日志写入 journal，以下日志轮转配置适用于原有文件日志部署，不是 Go 服务的必需安装步骤。

```bash
systemctl status airopscat.service --no-pager
curl --fail http://127.0.0.1:8080/healthz
journalctl -u airopscat.service -n 50 --no-pager
```

### 升级与恢复

再次运行指定版本安装命令即可。备份和恢复的前提是该数据库只有本 systemd 服务写入；升级前应停止手工启动的应用、导入器和其他写入进程。安装期间使用 `/run/airopscat/install.lock` 排除并发安装。

安装器先停止服务，随后把存在的 `airopscat.db`、`airopscat.db-wal`、`airopscat.db-shm` 一起复制到同一个备份目录，并保存旧二进制、单元、主配置及管理员文件。不执行 `VACUUM`，不在运行中裸拷贝 WAL。备份失败时不替换程序，原先运行的服务会尝试重新启动。

替换后有界等待服务 active、MainPID 对应新二进制、该 PID 监听 8080，以及 `/healthz` 成功。失败或收到 INT/TERM 时，先停止新版，再恢复旧二进制、单元、配置和完整数据库组；旧组不存在的 WAL/SHM 会删除。数据库迁移可能已执行，因此绝不能只换回旧二进制。原先运行的服务恢复启动，原先停止的服务保持停止；原先未启用的服务撤销启用。成功安装会启动服务并启用开机自启。

如果停服或恢复本身失败，脚本保留备份并报错，不宣称恢复成功。断电、SIGKILL、磁盘损坏无法由 trap 自动恢复，需人工处理。人工恢复时先确定备份目录确为目标升级前备份，并按以下顺序执行：

```bash
systemctl stop airopscat.service
# 在 root shell 设置为脚本报告的实际目录，勿使用猜测的路径。
backup=/var/backups/airopscat/<实际目录>
set -e
# complete 仅在全部文件备份成功后创建；不完整备份禁止恢复。
test -f "$backup/complete"
test -f "$backup/was-active"
test -f "$backup/was-enabled"
if [ "$(cat "$backup/was-enabled")" = 0 ] && \
    [ "$(systemctl show airopscat.service --property LoadState --value)" != not-found ]; then
    systemctl disable airopscat.service
fi
for path in /usr/local/bin/airopscat /etc/systemd/system/airopscat.service \
    /etc/airopscat/airopscat.env /etc/airopscat/initial-admin.env \
    /var/lib/airopscat/airopscat.db /var/lib/airopscat/airopscat.db-wal \
    /var/lib/airopscat/airopscat.db-shm; do
    saved="$backup/$(basename "$path")"
    if [ -f "$saved" ]; then
        cp -p -- "$saved" "$path.restore"
        mv -f -- "$path.restore" "$path"
    else
        rm -f -- "$path"
    fi
done
for path in /var/lib/airopscat/airopscat.db{,-wal,-shm}; do
    if [ -f "$path" ]; then chown airopscat:airopscat "$path"; fi
done
systemctl daemon-reload
if [ "$(cat "$backup/was-active")" = 1 ]; then
    systemctl start airopscat.service
fi
```

恢复会丢弃备份时点之后的数据；保留失败现场用于排查时，也必须先停服再保存完整数据库组。备份可能含 secret、初始密码及业务数据，不应上传为公开附件，按运维保留策略人工清理。脚本不会自动删除历史备份。

### 本地自检查

```bash
bash -n deploy/install.sh
bash deploy/install.sh --self-test
```

自检查无需 root，只使用临时目录和 mock systemctl/chown，验证首次配置和凭据权限、密码不重建、checksum 拒绝，以及新版已改写 schema 后整组回滚（包括移除旧组不存在的 WAL/SHM）。另覆盖首次安装失败后移除本次文件并可重试。首次失败可留下独立用户和空目录，备份保留用于排查。它不访问网络、不操作宿主服务，不能替代真实 Linux systemd、用户所有权、SQLite 迁移与双架构运行测试。

## 日志轮转

| 仓库文件 | 服务器安装路径 |
| --- | --- |
| `logrotate/airopscat` | `/etc/logrotate.d/airopscat` |
| `systemd/airopscat-logrotate.service` | `/etc/systemd/system/airopscat-logrotate.service` |
| `systemd/airopscat-logrotate.timer` | `/etc/systemd/system/airopscat-logrotate.timer` |

策略：

- 每日轮转，每小时检查一次。
- 最多保留 14 份，且最长保留 14 天。
- 单个日志超过 100 MB 时提前轮转。
- 压缩历史日志，使用 `copytruncate` 避免重启 AirOpsCat。

### 安装

```bash
install -o root -g root -m 0644 deploy/logrotate/airopscat \
    /etc/logrotate.d/airopscat
install -o root -g root -m 0644 deploy/systemd/airopscat-logrotate.service \
    /etc/systemd/system/airopscat-logrotate.service
install -o root -g root -m 0644 deploy/systemd/airopscat-logrotate.timer \
    /etc/systemd/system/airopscat-logrotate.timer

logrotate --debug /etc/logrotate.d/airopscat
systemd-analyze verify \
    /etc/systemd/system/airopscat-logrotate.service \
    /etc/systemd/system/airopscat-logrotate.timer
systemctl daemon-reload
systemctl enable --now airopscat-logrotate.timer
systemctl start airopscat-logrotate.service
```

### 验证

```bash
systemctl status airopscat-logrotate.timer --no-pager
systemctl show airopscat-logrotate.service -p Result
systemctl list-timers airopscat-logrotate.timer --no-pager
```

### 卸载

```bash
systemctl disable --now airopscat-logrotate.timer
rm -f /etc/systemd/system/airopscat-logrotate.timer
rm -f /etc/systemd/system/airopscat-logrotate.service
rm -f /etc/logrotate.d/airopscat
systemctl daemon-reload
```
