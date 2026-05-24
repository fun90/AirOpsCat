## Context

sing-box 源码已扩展，inbound users 支持 `download_mbps` / `upload_mbps` 字段实现内核级用户限速。当前 AirOpsCat 的限速路径完全独立于部署路径：`Account.speed`（KB/s）经 `RateLimitService` 写到服务器的 `accounts.json`，再由 Python Agent 轮询 Clash API 断连模拟限速。该方式控制精度低、有额外进程依赖。

本次在不破坏现有超配降速机制的前提下，为账号新增 Mbps 级上下行字段，并将其注入 sing-box 配置，实现内核级原生限速。

## Goals / Non-Goals

**Goals:**
- Account 新增 `download_mbps` / `upload_mbps`（可空，表示不限速）
- 部署时将账号速度透传至 sing-box inbound users
- 现有 `speed`（KB/s）+ Agent 超配降速机制完整保留

**Non-Goals:**
- 不修改 Python Agent 逻辑
- 不修改 `RateLimitService`、`AccountTrafficLimitService`
- 不为超配场景引入新的原生重部署机制
- 不修改订阅生成逻辑

## Decisions

### 决策 1：新增字段而非改 speed 语义
`Account.speed` 保持 KB/s 不变供 Agent 使用；新增 `downloadMbps`（INT，可空）和 `uploadMbps`（INT，可空）供 sing-box 原生限速使用。

替代方案：将 speed 改成 Mbps 复用。但存量数据需要迁移，Agent 读取逻辑需要同步修改，改动面大且有回退风险。

### 决策 2：NodeClient 透传速度字段
在 `NodeClient` record 中增加 `downloadMbps` / `uploadMbps`，由 `DeploymentDataLoader.toVlessClient()` 从 Account 读取填充。

替代方案：在 `SingBoxConfigBuilder` 内部查询账号速度。但 Builder 不应有数据库依赖，且破坏了快照隔离性。

### 决策 3：仅非空时写入 sing-box 配置
`SingBoxConfigBuilder` 在构建用户对象时，仅当 `downloadMbps` / `uploadMbps` 非空且 > 0 时写入 `download_mbps` / `upload_mbps` 字段，未配置的账号 sing-box 默认不限速。

### 决策 4：upload_mbps 可单独省略
`download_mbps` 和 `upload_mbps` 相互独立。只配置 `download_mbps` 时，上行不限速（与 sing-box 行为一致）。

## Risks / Trade-offs

- **两套限速并存**：`speed`（KB/s）供 Agent 超配降速，`downloadMbps` 供 sing-box 原生限速，两者语义独立。管理员需理解差异，否则可能重复配置。→ UI 上用文案说明两者用途。
- **deploy 触发时机**：修改账号速度字段不会自动触发重新部署，需管理员手动重新部署节点。→ 与现有字段修改一致，不需要特殊处理。

## Migration Plan

1. 执行 Flyway 迁移脚本，account 表追加 `download_mbps`、`upload_mbps` 两列（可空，无默认值）
2. 发布新版应用（字段可空，存量账号不受影响）
3. 管理员按需为账号填写 Mbps 限速值，下次重新部署节点后生效

回退：Flyway 迁移列为 nullable，直接回退旧版本应用即可（旧代码忽略新列）。

## Open Questions

无。
