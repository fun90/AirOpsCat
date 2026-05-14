## Purpose

定义账户流量统计周期内的独立配额快照、有效配额查询优先级、订阅与使用率计算读取规则，以及管理端查看和调整周期配额的行为，确保历史周期配额不会被账户默认配额变更意外覆盖。

## Requirements

### Requirement: 周期流量记录存储配额快照

`AccountTrafficStats` 实体 SHALL 包含 `bandwidthQuota`（`Long`，单位 GB，可为 null）字段，记录该流量周期实际生效的流量配额。

#### Scenario: 新建流量周期记录时自动复制账户基准配额
- **WHEN** 系统为账户首次创建某周期的 `AccountTrafficStats` 记录（`saveOrUpdateTrafficStats` 触发新建分支）
- **THEN** 新记录的 `bandwidthQuota` 字段 SHALL 被设置为该账户 `Account.bandwidth` 的当前值（若 `Account.bandwidth` 为 null，则 `bandwidthQuota` 也为 null）

#### Scenario: 已存在记录时累加流量不影响配额
- **WHEN** 系统为账户在当前周期已存在 `AccountTrafficStats` 记录时累加流量
- **THEN** 系统 SHALL 仅更新 `uploadBytes` 和 `downloadBytes`，`bandwidthQuota` 保持不变

### Requirement: 管理员可修改指定周期的流量配额

系统 SHALL 提供 API 允许管理员单独修改某条 `AccountTrafficStats` 记录的 `bandwidthQuota`，而不影响账户基准 `Account.bandwidth` 及其他周期记录。

#### Scenario: 修改指定周期配额成功
- **WHEN** 管理员通过 `PATCH /api/admin/traffic-stats/{id}/quota` 提交新的 `bandwidthQuota` 值（正整数 GB 或 null）
- **THEN** 系统 SHALL 更新该记录的 `bandwidthQuota` 并返回 200 OK

#### Scenario: 将周期配额置为 null（继承账户基准）
- **WHEN** 管理员提交 `bandwidthQuota: null`
- **THEN** 系统 SHALL 将该字段设置为 null，后续有效配额查询将回退到账户基准

#### Scenario: 修改不存在的记录
- **WHEN** 管理员请求修改一个不存在的 `id`
- **THEN** 系统 SHALL 返回 404

### Requirement: 统一有效配额查询入口

系统 SHALL 提供 `AccountTrafficStatsService.getEffectiveBandwidth(Long accountId)` 方法，按以下优先级返回有效流量配额（GB）：

1. 查询当前时间点所在的 `AccountTrafficStats`（`period_start <= now <= period_end`），若存在且 `bandwidthQuota` 不为 null → 返回 `bandwidthQuota`
2. 否则 → 读取 `Account.bandwidth` 作为兜底返回值（可为 null）

#### Scenario: 当前周期存在配额记录且不为 null
- **WHEN** 调用 `getEffectiveBandwidth(accountId)`，当前时间点存在对应 `AccountTrafficStats` 且其 `bandwidthQuota = 150`
- **THEN** 方法 SHALL 返回 `150`

#### Scenario: 当前周期记录 bandwidthQuota 为 null，账户基准有值
- **WHEN** 调用 `getEffectiveBandwidth(accountId)`，当前周期记录的 `bandwidthQuota` 为 null，`Account.bandwidth = 100`
- **THEN** 方法 SHALL 返回 `100`

#### Scenario: 当前周期不存在记录，账户基准有值
- **WHEN** 调用 `getEffectiveBandwidth(accountId)`，当前时间点不存在 `AccountTrafficStats`，`Account.bandwidth = 200`
- **THEN** 方法 SHALL 返回 `200`

#### Scenario: 当前周期不存在记录，账户基准也为 null
- **WHEN** 调用 `getEffectiveBandwidth(accountId)`，不存在记录且 `Account.bandwidth` 为 null
- **THEN** 方法 SHALL 返回 `null`（表示不限量）

### Requirement: 所有配额读取点使用统一查询入口

系统中所有需要账户流量配额的逻辑 SHALL 通过 `getEffectiveBandwidth(accountId)` 获取，而非直接读取 `account.getBandwidth()`。

受影响的调用点包括：
- `AccountService.toDto()`：使用率百分比计算（`usagePercentage`）
- `SubscriptionService`：生成订阅时写入 `upload` / `download` 流量上限字段

#### Scenario: 账户 DTO 使用率计算使用周期配额
- **WHEN** `AccountService.toDto()` 计算 `usagePercentage`
- **THEN** 系统 SHALL 调用 `getEffectiveBandwidth(account.getId())` 获取配额，而非 `account.getBandwidth()`

#### Scenario: 当前周期配额为 null 时不计算使用率
- **WHEN** `getEffectiveBandwidth()` 返回 null
- **THEN** `usagePercentage` SHALL 不被设置（或保持 0 / null），不执行除以零的运算

#### Scenario: 订阅生成使用周期配额
- **WHEN** `SubscriptionService` 生成用户订阅时读取流量上限
- **THEN** 系统 SHALL 使用 `getEffectiveBandwidth(accountId)` 的结果，而非 `account.getBandwidth()`

### Requirement: 流量统计页面展示并支持编辑周期配额

账户流量统计管理页面 SHALL 展示每条 `AccountTrafficStats` 记录的 `bandwidthQuota` 字段，并提供修改入口。

#### Scenario: 列表显示 bandwidthQuota
- **WHEN** 管理员访问账户流量统计列表
- **THEN** 列表 SHALL 显示 `bandwidthQuota` 列，null 值显示为"—"或"继承账户默认"

#### Scenario: 修改周期配额触发 API 调用
- **WHEN** 管理员在前端修改某条记录的周期配额并确认
- **THEN** 前端 SHALL 调用 `PATCH /api/admin/traffic-stats/{id}/quota` 并在成功后刷新列表
