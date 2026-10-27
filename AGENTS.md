# AirOpsCat 开发约定

## 通用约定

- 正式分支为 `release/go`。Go + SQLite（WAL）+ `html/template` 服务端渲染；只使用 sing-box 内核。
- 验证：`go vet ./...`、`go test ./...`；共享并发逻辑加跑相关 race 测试。
- 页面或订阅输出有可见变化：验证通过后自行启动本地实例交用户验收，见 [本地验收](docs/local-acceptance.md)。
- 未明确要求时不提交、不推送、不创建发布 tag。正式部署和性能测试分开；持续观察未完成时标记待验收。

## 任务路由

| 任务 | 必读 |
|------|------|
| 改 `../profile/airopscat/shell/` 运维脚本 | [服务器运维脚本规范](docs/server-scripts.md) |
| 改 Go 代码、系统配置、外部契约、敏感字段 | [实现约定](docs/implementation.md) |
| 改页面、模板、样式、前端脚本；页面验证 | [UI 开发与验证](docs/ui.md) |
| 本地用生产数据调试、验收 | [本地验收](docs/local-acceptance.md) |
| 安装、发布 | `deploy/README.md` |
| 迁移、回滚 | `docs/migration.md` |
| 写验证记录 | `docs/acceptance.md` |

## 文档修改规则

- 事实与方案文档就地更新，只保留最新口径，删除过期内容；不追加「补充说明 / 勘误 / 变更记录」段落。错题表的历史错误证据按其记录与晋升机制维护。
