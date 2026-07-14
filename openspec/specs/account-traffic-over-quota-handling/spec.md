# account-traffic-over-quota-handling Specification

## Purpose
TBD - created by archiving change account-over-quota-speed-limit-alert. Update Purpose after archive.
## Requirements
### Requirement: 账户流量超额必须记录超额状态

系统 SHALL 在账户流量统计累加后检查账户当前周期总用量是否达到或超过有效流量配额，并在超额时记录超额告警状态，以便部署客户端数据生成时计算有效限速。

#### Scenario: 当前周期用量达到有效配额
- **WHEN** `AccountTrafficStatsService.saveOrUpdateTrafficStats()` 累加账户当前周期上传和下载流量后，总用量大于等于该账户有效配额换算后的字节数
- **THEN** 系统 SHALL 将该账户识别为流量超额账户
- **AND** 系统 SHALL 创建或更新该账户的流量超额告警状态
- **AND** 系统 SHALL 不修改账户自身 `speed` 字段

#### Scenario: 当前周期用量未达到有效配额
- **WHEN** 账户当前周期总用量小于有效配额换算后的字节数
- **THEN** 系统 SHALL 不因本次统计将账户标记为超额

#### Scenario: 账户没有有效配额
- **WHEN** 账户有效配额为 `null` 或小于等于 0
- **THEN** 系统 SHALL 视为不限量，并不触发流量超额状态

### Requirement: 超额限速值必须来自系统配置

系统 SHALL 在现有 `SystemConfig` 的账号设置分组中提供账户流量超额限速配置，默认值为 `20`，单位为 `KB/s`。

#### Scenario: 默认超额限速配置初始化
- **WHEN** 系统初始化默认配置
- **THEN** `account` 配置分组 SHALL 包含 `airopscat.account.traffic-over-quota.speed-kb`
- **AND** 该配置默认值 SHALL 为 `20`

#### Scenario: 读取超额限速配置
- **WHEN** 系统处理账户流量超额
- **THEN** 系统 SHALL 从 `airopscat.account.traffic-over-quota.speed-kb` 读取限速值
- **AND** 当配置为空、非法或小于 1 时 SHALL 使用 `20` 作为兜底值

### Requirement: 账户流量超额必须发送告警通知

系统 SHALL 在账户首次进入流量超额状态时通过现有通知通道发送告警，并通过 `AlertState` 控制重复通知。

#### Scenario: 首次检测到账户流量超额
- **WHEN** 系统检测到账户当前周期流量超额且不存在对应 ACTIVE 告警状态
- **THEN** 系统 SHALL 创建 `alertType = account-traffic-over-quota` 的 `AlertState`
- **AND** 系统 SHALL 发送包含账户、当前用量、有效配额和限速值的告警通知

#### Scenario: 账户持续流量超额
- **WHEN** 系统再次检测到账户流量超额且已存在对应 ACTIVE 告警状态
- **THEN** 系统 SHALL 更新最近触发时间、触发次数、当前值、阈值和摘要
- **AND** 系统 SHALL 按最小通知间隔决定是否重复发送告警

#### Scenario: 告警发送失败
- **WHEN** Bark 告警通知发送失败
- **THEN** 系统 SHALL 保留 ACTIVE 告警状态并记录失败日志

### Requirement: 账户流量恢复必须记录告警恢复状态

系统 SHALL 在账户不再超过有效配额时，将对应流量超额告警状态更新为恢复，但不自动清空账户 `speed`。

#### Scenario: 已超额账户不再超过有效配额
- **WHEN** 账户此前存在 ACTIVE 流量超额告警状态，且当前周期总用量小于有效配额
- **THEN** 系统 SHALL 将该告警状态更新为 RECOVERED
- **AND** 系统 SHALL 不自动修改账户当前 `speed`

#### Scenario: 有效配额被移除
- **WHEN** 账户此前存在 ACTIVE 流量超额告警状态，且当前有效配额变为 `null` 或小于等于 0
- **THEN** 系统 SHALL 将该告警状态更新为 RECOVERED
- **AND** 系统 SHALL 不自动修改账户当前 `speed`

### Requirement: 超额状态变化后必须请求配置刷新

系统 SHALL 在账户流量统计任务检测到账户流量超额或超额状态恢复后，请求限速配置刷新流程，使远端 sing-box 配置尽快包含重新计算后的有效限速（含超配覆盖）。

#### Scenario: 账户进入流量超额状态
- **WHEN** 系统检测到账户从未超额变为流量超额
- **THEN** 系统 SHALL 触发限速同步（`RateLimitService.triggerAsyncSync()`）
- **AND** 该同步 SHALL 同时更新 `accounts.json`（Agent 路径）和 sing-box 配置（原生限速路径）

#### Scenario: 账户从流量超额恢复
- **WHEN** 系统检测到账户从流量超额恢复为未超额
- **THEN** 系统 SHALL 触发限速同步（`RateLimitService.triggerAsyncSync()`）
- **AND** 该同步 SHALL 同时更新 `accounts.json` 和 sing-box 配置，恢复账号原始限速值

#### Scenario: 全局限速开关关闭
- **WHEN** 账户流量超额且全局限速开关关闭
- **THEN** 系统 SHALL 仍记录告警状态并发送告警
- **AND** 远端实际限速 SHALL 由现有限速同步开关控制，sing-box 配置重推同样受该开关约束

### Requirement: 账户详情必须标识流量超额限速

系统 SHALL 在账户详情数据中返回当前实际生效速度限制，以及该有效速度是否由流量超额限速产生，使前端账户详情页面能够标识当前超额限速。

#### Scenario: 账户超额且未配置账户自身限速
- **WHEN** 管理员查看账户详情，且账户当前周期流量已超过有效配额，并且账户 `speed` 为 `null` 或小于等于 0
- **THEN** 账户详情响应 SHALL 返回有效速度限制为系统配置的超额限速值
- **AND** `trafficOverQuotaLimited` SHALL 为 `true`
- **AND** 前端“速度限制” SHALL 展示该速度值并显示“流量超额限速”标识

#### Scenario: 账户超额且账户自身限速更高
- **WHEN** 管理员查看账户详情，且账户当前周期流量已超过有效配额，并且账户 `speed` 大于超额限速配置值
- **THEN** 账户详情响应 SHALL 返回有效速度限制为系统配置的超额限速值
- **AND** `trafficOverQuotaLimited` SHALL 为 `true`
- **AND** 前端“速度限制” SHALL 展示该速度值并显示“流量超额限速”标识

#### Scenario: 账户超额但账户自身限速更低
- **WHEN** 管理员查看账户详情，且账户当前周期流量已超过有效配额，并且账户 `speed` 大于 0 且小于等于超额限速配置值
- **THEN** 账户详情响应 SHALL 返回有效速度限制为账户 `speed`
- **AND** `trafficOverQuotaLimited` SHALL 为 `false`
- **AND** 前端“速度限制” SHALL 展示账户自身限速，不显示流量超额限速标识

#### Scenario: 账户未超额
- **WHEN** 管理员查看账户详情，且账户当前周期流量未超过有效配额
- **THEN** 账户详情响应 SHALL 按账户 `speed` 返回有效速度限制
- **AND** `trafficOverQuotaLimited` SHALL 为 `false`
- **AND** 前端“速度限制” SHALL 不显示流量超额限速标识

