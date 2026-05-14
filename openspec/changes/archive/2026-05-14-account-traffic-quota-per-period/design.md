## Context

当前账户流量配额（`Account.bandwidth`，单位 GB）是账户级全局值，所有流量周期共享同一配额。`AccountTrafficStats` 记录各周期的流量消耗，但不存储该周期对应的配额。这导致：

1. 无法为某个周期单独调整配额（如活动赠送、违规限流）
2. 删除或修改账户 `bandwidth` 后，历史周期的配额信息丢失
3. 使用率计算（`AccountService`）、订阅配额（`SubscriptionService`）均直接读取 `account.getBandwidth()`，散布在多处

## Goals / Non-Goals

**Goals:**
- `AccountTrafficStats` 新增 `bandwidth_quota`（GB）列，作为该周期实际生效配额
- 创建新 `AccountTrafficStats` 记录时自动从 `Account.bandwidth` 复制配额（可以为 null，表示不限量）
- 提供"当前周期配额优先，账户基准兜底"的统一查询入口 `getEffectiveBandwidth(accountId)`
- 支持通过管理接口修改指定周期记录的 `bandwidthQuota`
- 所有使用流量配额的调用点迁移至统一查询入口

**Non-Goals:**
- 不引入配额模板或批量周期配额规划
- 不改变 `Account.bandwidth` 的含义（保持为基准默认值）
- 不为 `Server.bandwidth` 做相同改造（范围外）

## Decisions

### 决策 1：配额存储位置选择 `AccountTrafficStats.bandwidth_quota`

**备选方案：** 新建独立的 `AccountQuotaOverride` 表，按周期存储覆盖值。

**选择理由：** 配额与周期强绑定，内聚在 `AccountTrafficStats` 中最简单，无需额外关联查询；且每个周期只有一条统计记录，不存在行膨胀问题。独立表只在"一个账户同一周期有多种配额版本"场景才有必要，当前不需要。

### 决策 2：创建记录时复制而非引用

**备选方案：** 计算时动态读取账户当前 `bandwidth`。

**选择理由：** 复制到记录中可保证历史数据稳定——即使后续修改账户基准，已完成周期的配额不受影响，同时也为按周期修改提供了落点。动态读取则无法支持单独修改某周期的需求。

### 决策 3：统一查询入口 `getEffectiveBandwidth(accountId)`

查询逻辑：
1. 取当前时间，查询涵盖该时间点的 `AccountTrafficStats`（`period_start <= now <= period_end`）
2. 若记录存在且 `bandwidth_quota` 不为 null → 返回该值
3. 否则 → 读取 `Account.bandwidth` 作为兜底

该方法放在 `AccountTrafficStatsService`，调用方（`AccountService`、`SubscriptionService`）注入该服务调用，无需感知回退逻辑。

### 决策 4：数据库迁移策略

新列 `bandwidth_quota BIGINT NULL`，允许 null（表示当前周期沿用账户基准，不做硬编码）。现有历史记录保持 null，无需回填——`getEffectiveBandwidth` 的兜底逻辑已覆盖 null 的情况。

## Risks / Trade-offs

- **[风险] 已有记录 bandwidth_quota 为 null** → 兜底读取账户基准，行为与改动前一致，无影响
- **[风险] 账户被删除但历史记录仍存在** → `getEffectiveBandwidth` 中账户查询返回 null 时，返回记录的 `bandwidth_quota`（若也为 null 则返回 null/0，表示不限量），逻辑清晰
- **[权衡] 散点替换调用方** → `AccountService` 和 `SubscriptionService` 需要注入 `AccountTrafficStatsService`，若已有循环依赖需检查；建议优先在 `AccountService.toDto()` 中替换，`SubscriptionService` 独立替换

## Migration Plan

1. 执行 DDL：`ALTER TABLE account_traffic_stats ADD COLUMN bandwidth_quota BIGINT NULL;`
2. 部署新版本（新列 null 安全，兜底逻辑覆盖 null）
3. 无需回填历史数据，兜底逻辑保证向后兼容
4. 回滚：删除新列即可，调用方回退读 `account.getBandwidth()`

## Open Questions

- `bandwidth_quota` 单位统一使用 GB（与 `Account.bandwidth` 一致），是否需要转换为字节存储？→ 建议保持 GB，单位统一，减少转换错误
- 前端编辑 `bandwidth_quota` 的入口：是在流量统计列表行内编辑，还是独立弹窗？→ 建议行内 inline-edit 或列表操作弹窗，与现有 UI 模式一致
