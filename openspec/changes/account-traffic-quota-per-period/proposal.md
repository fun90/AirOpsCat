## Why

账户（Account）中的 `bandwidth` 字段是全局基准值，无法针对每个流量周期单独设定配额。当前系统在计算使用率、发送到期通知和生成订阅时，都直接读取账户的 `bandwidth`，导致无法为特定周期临时调整配额（如活动赠送、违规限流等）。

## What Changes

- `AccountTrafficStats` 增加 `bandwidthQuota`（GB）字段，记录该周期实际生效的流量配额
- 创建 `AccountTrafficStats` 记录时，自动从 `Account.bandwidth` 复制基准配额到 `bandwidthQuota`
- 提供 API 支持修改指定周期流量记录的 `bandwidthQuota`
- 所有需要获取账户流量配额的逻辑（使用率计算、到期通知阈值、订阅配额）统一走"当前周期配额优先，账户基准兜底"的查询逻辑

## Capabilities

### New Capabilities

- `account-traffic-quota-per-period`：账户流量周期配额管理能力，支持按周期独立设置和覆盖基准配额

### Modified Capabilities

（无现有规格需要更新）

## Impact

- **实体**：`AccountTrafficStats` 新增 `bandwidthQuota` 字段（数据库加列）
- **服务**：`AccountTrafficStatsService.saveOrUpdateTrafficStats()` 创建新记录时复制账户 bandwidth；新增 `getEffectiveBandwidth(accountId)` 方法
- **服务**：`AccountService`、`SubscriptionService`、流量阈值告警服务中替换直接读取 `account.getBandwidth()` 的调用
- **控制器**：`AccountTrafficStatsController` 新增修改 `bandwidthQuota` 的端点
- **前端**：账户流量统计列表/详情页展示并支持编辑 `bandwidthQuota`
- **数据库**：需要执行一次迁移 SQL 为已有记录回填 `bandwidth_quota`
