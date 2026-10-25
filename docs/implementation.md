# 实现约定

- 目录：配置生成 `internal/singbox/`，部署 `internal/deploy/`，数据访问 `internal/store/`，控制台 `internal/web/` 与 `web/templates/`。
- 增加系统配置必须加入 `store.SettingDefaults`，接入实际消费方和校验；不展示失效配置。任务周期固定在代码，不提供无效周期配置。
- 外部订阅、docs-info、guard-sync 契约保持兼容。敏感字段用现有加密器，禁止在日志、页面或命令行输出凭据。
