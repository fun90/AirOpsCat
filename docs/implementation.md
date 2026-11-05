# 实现约定

- 目录：配置生成 `internal/singbox/`，部署 `internal/deploy/`，数据访问 `internal/store/`，控制台 `internal/web/` 与 `web/templates/`。
- 增加系统配置必须加入 `store.SettingDefaults`，接入实际消费方和校验；不展示失效配置。任务周期固定在代码，不提供无效周期配置。
- 端口探测使用自动分发的 Go 原生程序，经 SSH 标准输入接收 JSON 请求及 QUIC/salamander 报文，不使用 shell 网络重定向，不在命令行携带报文或混淆密码。构建前运行 `go generate ./internal/probeassets`，内嵌 Linux amd64/arm64 探测器；远端安装或程序校验失败属于探测机错误，不累计目标失败次数或清空旧结果。
- 外部订阅、docs-info、guard-sync 契约保持兼容。敏感字段用现有加密器，禁止在日志、页面或命令行输出凭据。
