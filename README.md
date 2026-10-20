# AirOpsCat

基于 Go、SQLite 与 sing-box 的服务器、节点及订阅管理控制台。单二进制内嵌界面和订阅模板，无需独立数据库服务。

## 功能

- 服务器、节点、账户、用户、标签、订阅定制与收支管理。
- sing-box 配置生成、SSH 部署、主机密钥校验、流量采集与配额限速。
- Cloudflare DNS、时段解析、按流量与便宜时段自动切换。
- 服务器 CPU/内存/网速及 vnstat 监控、告警状态机和 Bark 通知。
- 维护脚本批量执行、SQLite 一致性备份和历史清理。

## 本地启动

需要 Go 1.26。首次启动使用环境变量设置管理员；敏感值不要提交到 Git。

```bash
export AIROPSCAT_SECRET_KEY="$(openssl rand -base64 48)"
export AIROPSCAT_ADMIN_EMAIL='admin@example.com'
read -rs AIROPSCAT_ADMIN_PASSWORD
export AIROPSCAT_ADMIN_PASSWORD
export AIROPSCAT_DB="$PWD/airopscat.db"
export AIROPSCAT_BACKUP_DIR="$PWD/backup"
go run ./cmd/airopscat
```

访问 `http://127.0.0.1:8080`。密钥须持久保存，变更密钥会使现有敏感字段无法解密。

## 运行环境

| 变量 | 默认值 / 含义 |
|---|---|
| `AIROPSCAT_SECRET_KEY` | 必填，至少 32 字符 |
| `AIROPSCAT_DB` | `/var/lib/airopscat/airopscat.db` |
| `AIROPSCAT_LISTEN` | `127.0.0.1:8080`，建议经 HTTPS 反向代理访问 |
| `AIROPSCAT_ADMIN_EMAIL` / `AIROPSCAT_ADMIN_PASSWORD` | 首次初始化使用 |
| `AIROPSCAT_BACKUP_DIR` | `/var/lib/airopscat/backup` |
| `AIROPSCAT_SCRIPTS_DIR` | 覆盖系统配置中的脚本目录，指向 profile 仓库的 `airopscat/shell` |
| `AIROPSCAT_TRAFFIC_SERVERS` | 空为关闭；服务器 ID 逗号列表或 `all`，控制流量采集及远端配置备份清理 |
| `AIROPSCAT_MONITOR_SERVERS` | 空为关闭；服务器 ID 逗号列表或 `all` |
| `AIROPSCAT_DNS_AUTO_DOMAINS` | 空为关闭；域名 ID 逗号列表或 `all` |
| `AIROPSCAT_NOTIFY_DISABLED=1` | 禁止全部 Bark 外发，含测试通知 |
| `AIROPSCAT_TASKS_DISABLED=1` | 禁止启动后台 ticker，供隔离验收；管理页手动执行仍可用 |

系统配置只展示实际使用的选项，缺失项在启动时补齐，旧数据库的其他键不删除。任务周期固定；备份保留天数、日志保留天数等参数在系统配置中修改。

## 开发与发布

```bash
go vet ./...
go test ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags '-s -w' -o airopscat ./cmd/airopscat
```

正式代码位于 `release/go`。安装、更新与 GitHub 发布见 [部署说明](deploy/README.md)。迁移见 [迁移与回滚](docs/migration.md)，上线门槛与实测结果见 [验收记录](docs/acceptance.md)。

过往实现及历史设计保留在 Git 历史中，发布树仅包含当前运行所需内容。
