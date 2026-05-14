## Context

当前账户流量配额已经通过 `AccountTrafficStats.bandwidthQuota` 支持周期快照和 `getEffectiveBandwidth(accountId)` 统一查询。节点部署数据加载仍在 `DeploymentDataLoader#buildNodeClientsMap` 中按 `Account.bandwidth` 与当前周期用量过滤超额账户，导致超额账户不再生成到 sing-box 用户列表。

限速链路已经存在：`Account.speed` 表示账号自身限速（`KB/s`，`0` 或 `null` 表示不限速），`RateLimitService` 会将 `accountNo` 和 `speed` 写入服务器本地限速配置。新方案不能把超额限速直接写入 `Account.speed`，否则会覆盖账户本身配置；应在限速同步数据生成时，根据账户是否超额计算本次下发的有效速度。

## Goals / Non-Goals

**Goals:**

- 流量超额账户继续参与节点部署，不再因超额从远端用户配置中消失。
- 账户流量统计累加后，如果当前周期使用量超过有效配额，则记录超额告警状态并触发后续配置刷新。
- 超额限速配置放入现有 `SystemConfig` 的账号设置分组，默认 `20`，单位 `KB/s`。
- 超额处置发送 Bark 告警，并使用 `AlertState` 控制重复通知。
- 限速同步数据生成时根据是否超额计算有效速度限制，不能直接把 `Account.speed` 当作最终速度。

**Non-Goals:**

- 不新增独立限速表或复杂限速策略模板。
- 不改变 `Account.bandwidth` / `AccountTrafficStats.bandwidthQuota` 的单位和含义。
- 不改变账户自身 `speed` 配置；超额限速是运行时有效速度，不作为账户基础配置持久化。
- 不改变远端限速代理协议，仍使用现有 `accounts.json` 中的 `speed` 字段。

## Decisions

### 决策 1：超额限速不写入 `Account.speed`

**备选方案：** 在流量统计时直接把 `Account.speed` 改为超额限速值。

**选择理由：** `Account.speed` 是管理员配置的账户自身限速，不应被流量超额处置覆盖。超额限速是由当前周期流量状态推导出来的运行时速度，应在生成限速同步数据和账户详情展示数据时动态计算。这样既能保留管理员原始配置，也能在配额调整或周期切换后自然恢复到账号自身限速。

### 决策 2：去除部署阶段流量过滤

`DeploymentDataLoader#buildNodeClientsMap` SHALL 移除 `isWithinBandwidth(account, usedBytes)` 过滤，不再因为流量超额剔除账户。节点客户端列表只根据标签授权、账户有效期、账户禁用状态等条件决定是否包含账户。`NodeClient` 不包含速度字段；流量超额限速由 `RateLimitService` 的限速配置同步链路下发。

### 决策 3：统计累加后触发超额处置

`TrafficStatsTask` 在调用 `AccountTrafficStatsService.saveOrUpdateTrafficStats()` 新建或更新当前周期记录后，调用超额处置服务。处置服务读取当前周期总用量、有效配额（优先 `bandwidthQuota`，兜底 `Account.bandwidth`），当总用量大于等于配额字节数时执行：

1. 读取 `airopscat.account.traffic-over-quota.speed-kb`，默认 `20`，最小值按 `1` 兜底；
2. 创建或更新 `AlertState`（`alertType = account-traffic-over-quota`）；
3. 按最小通知间隔发送 Bark 告警；
4. 触发或请求限速配置刷新（`triggerRateLimitSync` / `RateLimitService.syncAll()`），使远端尽快拿到重新计算后的有效速度。

### 决策 4：限速同步速度按超额状态计算

生成限速同步数据时，系统 SHALL 为每个账户计算有效速度：

1. 若账户未超额：有效速度为 `Account.speed`（`null` 或小于等于 0 表示不限速）；
2. 若账户已超额且 `Account.speed` 为空或小于等于 0：有效速度为超额限速配置值；
3. 若账户已超额且 `Account.speed` 大于 0：有效速度为 `min(Account.speed, 超额限速配置值)`，避免放宽已有更严格限速。

### 决策 5：恢复只更新告警状态，速度随部署动态恢复

若后续通过修改配额、重置周期或人工清流量使账户不再超额，系统只将 `AlertState` 更新为 `RECOVERED`。由于超额限速不写入 `Account.speed`，下一次限速同步数据生成时会自动回到 `Account.speed` 定义的账户自身限速。

### 决策 6：账户详情展示有效速度和超额限速标识

`AccountDto` SHALL 保留原有 `speed` 字段表示账户自身限速，同时新增用于展示的运行时字段，例如：

- `effectiveSpeed`：当前实际生效速度，单位 `KB/s`，`null` 或小于等于 0 表示不限速；
- `trafficOverQuotaLimited`：当前展示的有效速度是否由流量超额限速产生。

账户详情页面的“速度限制”字段 SHALL 使用上述运行时字段展示实际生效速度。当 `trafficOverQuotaLimited = true` 时，应在速度值旁显示“流量超额限速”等明确标识；如果账户自身 `speed` 更低导致实际速度来自账户自身限速，则 `trafficOverQuotaLimited` SHALL 为 `false`。

### 决策 7：配置项放入账号设置分组

新增配置项：

- Key：`airopscat.account.traffic-over-quota.speed-kb`
- 分组：`account`
- 类型：数字
- 默认值：`20`
- 文案：账户流量超额限速，单位 `KB/s`

该配置与已有 `airopscat.ratelimit.enabled` 同属账号限速相关配置。即使全局限速开关关闭，统计仍可记录告警状态；远端是否实际执行限速由现有限速开关和后续同步流程控制。

## Risks / Trade-offs

- **[风险] 动态速度计算与前端展示不一致** → 将有效速度计算封装为可复用方法，限速同步和账户详情展示共用同一逻辑。
- **[风险] 前端展示误把账户自身限速当成超额限速** → DTO 明确返回 `trafficOverQuotaLimited`，页面只在该字段为 `true` 时显示超额限速标识。
- **[风险] 超额后限速未及时同步到远端** → 处置完成后请求节点部署或 `RateLimitService.syncAll()`；如果全局限速关闭，仍保留告警记录，待开启后同步。
- **[风险] 同一账户持续统计导致重复告警** → 使用 `AlertState` 的最近通知时间和配置的最小间隔抑制重复通知。
- **[权衡] 限速同步需要查询流量状态** → 相比直接读账户字段多一次状态计算，但换来不污染账户配置和周期切换后自动恢复。

## Migration Plan

1. 部署新版本时初始化新增 `SystemConfig` 默认项。
2. 已存在账户不做批量扫描；后续流量统计任务触发时逐步识别超额账户并记录告警状态。
3. 回滚时保留 `AlertState` 记录；账户自身 `speed` 未被超额流程修改，无需批量恢复。

## Open Questions

- 告警最小通知间隔是否复用连接数告警的配置，还是新增独立配置？建议复用现有通用间隔或在实现时新增 `airopscat.account.traffic-over-quota.alert.min-interval-minutes`，默认 `60`。
- “超过限额”边界是 `>` 还是 `>=`？建议采用 `>=`，达到购买配额即进入超额处置。
