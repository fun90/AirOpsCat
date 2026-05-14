## Why

当前账户流量超过限额后会在节点部署数据加载阶段被过滤，导致账户从远端配置中消失，用户体验更接近“断网”而不是“降速”。同时流量统计超过限额时缺少统一的限速落地和告警通知，运维无法及时知道哪些账户已进入超额处置状态。

## What Changes

- 去除 `DeploymentDataLoader#buildNodeClientsMap` 中对流量超额账户的过滤，超额账户仍继续进入节点客户端配置。
- 新增账户流量超额限速配置项，放入现有 `SystemConfig` 的账号设置分组，默认值为 `20`，单位为 `KB/s`（展示文案使用 `20KB/s`）。
- 流量统计累加后若当前周期使用量超过有效配额，则识别为超额状态并发送告警通知。
- 限速同步数据生成时，根据账户是否超额计算有效速度限制：未超额时使用 `Account.speed`，超额时使用系统配置的超额限速值，并保留更严格的已有账户限速。
- 前端账户详情页面的“速度限制”需要标识当前是否正在因流量超额限速，并展示实际生效的超额限速值。
- 使用现有告警状态模型记录账户流量超额状态，避免重复告警，并支持恢复状态记录。

## Capabilities

### New Capabilities

- `account-traffic-over-quota-handling`：账户流量超过当前有效配额后的超额状态、告警和恢复状态管理。

### Modified Capabilities

- `server-and-node-deployment`：节点部署生成客户端列表时不再剔除流量超额账户。

## Impact

- **调度任务**：`TrafficStatsTask` 在账户流量统计累加后检查当前周期是否超额，并触发告警与后续限速同步。
- **服务**：新增或扩展账户流量超额处置服务，负责判断超额状态、读取超额限速配置、写入 `AlertState`、调用 `BarkService`。
- **部署**：`DeploymentDataLoader#buildNodeClientsMap` 移除 `isWithinBandwidth()` 过滤，超额账户仍进入节点客户端列表；`NodeClient` 不承载限速字段。
- **限速同步**：`RateLimitService` 输出 `accounts.json` 时按超额状态计算有效速度。
- **账户 DTO/API**：账户详情响应补充有效速度限制和是否因流量超额限速字段，供前端标识超额限速。
- **前端**：账户详情页面“速度限制”展示实际生效速度；当 `trafficOverQuotaLimited` 为 `true` 时显示明确标识。
- **配置**：`SystemConfigService` 的 `account` 分组新增 `airopscat.account.traffic-over-quota.speed-kb`，默认 `20`。
- **告警**：复用 `AlertState` 和 Bark 通知；告警类型建议为 `account-traffic-over-quota`。
