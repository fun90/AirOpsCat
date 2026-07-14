## Why

限速 Agent 整条链路（Python + nftables + TC HTB，或软断连式 Agent）依赖 Clash API 轮询、额外系统进程和内核模块，控制精度低、有延迟、体验差（TC/HTB 基于连接 IP/port 标记，软断连会强制中断用户连接）。sing-box 已原生支持 inbound users 级别的 `download_mbps`/`upload_mbps`，可在内核层实现无断连的平滑限速，且无外部进程依赖。

本次彻底废弃 Python Agent 整条链路，改由 Java 侧在部署时直接将运行时限速 override 写入 sing-box 配置（一次 RESTART 完成），状态变化时 RELOAD 更新。

## What Changes

- **删除** 4 个 Agent shell 脚本（TC/HTB Agent 安装/卸载、软断连 Agent 安装/卸载）
- **重写** `RateLimitService`：从"构建 accounts.json 并推送"改为"计算限速 override，构建含 override 的 sing-box config 并 RELOAD"
- **重写** `AccountTrafficLimitService`：移除 KB/s 体系（`speed-kb` 配置、`resolveEffectiveSpeed()`），新增 Mbps 超配降速解析（`resolveEffectiveMbps()`）
- **集成部署流程**：`CoreDeploymentExecutor.buildConfig()` 在构建 sing-box config 前先计算 override 并注入，一次 RESTART 完成部署+限速，移除部署完成后的异步 `syncServer()` 调用
- **新增** `SingBoxConfigBuilder` 带 override 重载：超配账号的 `download_mbps`/`upload_mbps` 被覆写为系统降速值，其余账号保持原始 Mbps 值
- **新增** 系统配置项 `airopscat.account.traffic-over-quota.download-mbps`（下行降速，默认 1 Mbps）和 `upload-mbps`（上行降速，默认 1 Mbps）
- **废弃** 系统配置项 `airopscat.account.traffic-over-quota.speed-kb`
- **不迁移** `Account.speed` 字段（DB 列保留，Java 侧停止使用，UI 后续评估是否移除输入框）

## Capabilities

### 新增
- `sing-box-native-ratelimit-integrated`：部署和限速合并为单次操作，sing-box 内核直接按账号限速，超配账号自动降速，无外部 Agent 和内核模块依赖

### 修改
- `account-traffic-over-quota-handling`：超配/恢复时触发 sing-box config 重推+RELOAD（不再写 accounts.json）
- `server-and-node-deployment`：部署时直接将运行时限速 override 注入 sing-box config，不再单独同步
- `account-native-user-ratelimit`：超配账号的 download/upload_mbps 被覆写为系统降速值，正常账号使用 Account 原始 Mbps 配置

### 移除
- `python-agent-ratelimit`：TC/HTB + nftables Agent 和软断连 Agent 完全废弃

## Impact

- **Shell 脚本**：删除 `02-ratelimit-agent.sh`、`03-ratelimit-agent-uninstall.sh`、`04-soft-ratelimit-agent.sh`、`05-soft-ratelimit-agent-uninstall.sh`
- **Java**：`RateLimitService`（核心重写）、`AccountTrafficLimitService`（KB/s 逻辑全部移除，新增 Mbps 方法）、`SingBoxConfigBuilder`（新增带 override 重载）、`CoreDeploymentExecutor`（注入 override，移除异步 syncServer）、`AccountService`（触发条件从 speed 改为 downloadMbps/uploadMbps）、`AccountTrafficOverQuotaService`（移除 KB/s 文案）、`SystemConfigService`（配置项增删）
- **不影响**：`Account.downloadMbps`/`uploadMbps` 字段（已有）、`NodeClient`（已有）、订阅生成、`AccountDto.trafficOverQuotaLimited`
