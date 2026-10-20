# AirOpsCat 开发约定

## 实现与验证

- 正式分支为 `release/go`。Go + SQLite（WAL）+ `html/template` 服务端渲染；只使用 sing-box 内核。
- 配置生成位于 `internal/singbox/`，部署位于 `internal/deploy/`，数据访问位于 `internal/store/`，控制台位于 `internal/web/` 与 `web/templates/`。
- 界面复用 Tabler；PC 列表操作达到 5 个、移动端达到 2 个才使用下拉；移动端按 390px 检查消息换行与横向溢出。
- 增加系统配置必须加入 `store.SettingDefaults`，接入实际消费方和校验；不展示失效配置。任务周期固定在代码，不提供无效周期配置。
- 外部订阅、docs-info、guard-sync 契约保持兼容。敏感字段用现有加密器，禁止在日志、页面或命令行输出凭据。
- 修改前检查 `git status --short`；保留用户已有改动。验证使用 `go vet ./...`、`go test ./...`，并对共享并发逻辑运行相关 race 测试。
- 安装发布见 `deploy/README.md`，迁移回滚见 `docs/migration.md`，验证记录见 `docs/acceptance.md`。过往实现和设计可从 `rewrite/go` 与 Git 历史查阅。
- 未明确要求时不提交、不推送、不创建发布 tag。正式部署和性能测试分开；持续观察尚未完成时必须标记待验收。

## 真机测试服务器

- 用户已指定 `<测试主机>` 可用于真机测试，包括部署、监控采集及维护脚本验证；后续相关任务沿用此约定。
- 当前导入数据中对应服务器 ID 为 `16`、IP 为 `<测试IP>`；ID 和 IP 仅作定位参考，连接前核对目标身份。
- 真机验证只选择这台服务器，不使用“部署全部”等批量操作，不操作其他服务器。
- 凭据沿用现有服务器配置，不记录私钥、密码和 Token；验证完成后记录结果并清理临时文件。

## 服务器运维脚本

修改 `../profile/airopscat/shell/` 时遵守：

- 脚本名使用唯一两位数字前缀，按依赖顺序编号；废弃脚本从活动目录移除。
- 可执行脚本放 `/opt/airopscat/scripts`，持久状态 `/var/lib/airopscat`，日志 `/var/log/airopscat`，临时锁和 PID `/run/airopscat`，下载缓存 `/var/cache/airopscat`，专属配置 `/etc/airopscat`；第三方软件沿用标准目录。
- 目录创建和安装须幂等，安装后清理缓存；cron/systemd 使用绝对路径；cron 以成对 `# BEGIN AIROPSCAT ...` / `# END AIROPSCAT ...` 标记。
- 卸载只删除自身已知文件，不递归删除非空目录。
