## Purpose
Define account maximum-connection limits, online connection over-limit alerting, and the reusable alert-state model used by this alert type.

## Requirements

### Requirement: 账户限制必须使用最大连接数定义
系统 SHALL 将账户并发限制定义为最大连接数，并清理业务、接口和管理界面中的最大在线 IP 数定义。

#### Scenario: 管理员配置账户限制
- **WHEN** 管理员创建或编辑账户并配置并发限制
- **THEN** 系统以最大连接数字段保存该限制

#### Scenario: 历史账户存在最大在线 IP 数值
- **WHEN** 系统迁移历史账户数据
- **THEN** 系统将历史最大在线 IP 数值作为最大连接数初始值迁移，并不再将其展示为最大在线 IP 数

#### Scenario: 旧最大在线 IP 数代码被清理
- **WHEN** 最大连接数迁移完成
- **THEN** 应用层实体、DTO、请求对象和前端页面不再读写 `maxOnlineIps`

#### Scenario: 管理界面展示账户限制
- **WHEN** 管理员查看账户列表、账户详情或账户表单
- **THEN** 系统展示最大连接数，不展示最大在线 IP 数

### Requirement: 在线刷新后必须检查账户连接数超限
系统 SHALL 在账户在线刷新任务完成后，基于当前有效在线窗口统计每个账户的在线连接数，并与账户配置的最大连接数比较。

#### Scenario: 账户在线连接数超过限制
- **WHEN** 在线刷新完成且某个账户的当前在线连接数大于该账户最大连接数
- **THEN** 系统将该账户识别为连接数超限账户

#### Scenario: 账户未配置最大连接数限制
- **WHEN** 账户最大连接数为空或小于等于 0
- **THEN** 系统不对该账户执行连接数超限告警

#### Scenario: 同一客户端 IP 建立多条连接
- **WHEN** 同一账户的同一客户端 IP 在一个或多个节点上存在多条当前有效在线连接
- **THEN** 系统按实际在线连接记录数累计连接数

### Requirement: 连接数超限必须发送告警通知
系统 SHALL 在账户连接数超过限制时通过现有通知通道发送告警，并通过持久化告警状态控制重复通知。

#### Scenario: 账户连接数超限
- **WHEN** 账户当前在线连接数超过限制且连接数超限告警已启用
- **THEN** 系统发送包含账户、限制值、当前在线连接数、去重客户端 IP 数和节点分布摘要的告警通知

#### Scenario: 告警开关关闭
- **WHEN** 账户当前在线连接数超过限制但连接数超限告警配置为关闭
- **THEN** 系统不发送连接数超限告警

### Requirement: 连接数超限告警必须可配置
系统 SHALL 在系统配置中提供连接数超限告警开关，并允许配置最小通知间隔以减少重复通知。

#### Scenario: 最小通知间隔已配置
- **WHEN** 连接数超限告警最小通知间隔被配置
- **THEN** 系统结合持久化告警状态的最近通知时间减少过于频繁的重复告警

### Requirement: 连接数超限必须使用通用告警状态持久化
系统 SHALL 新增可复用于多类告警的告警状态持久化模型，并在本期仅将账户连接数超限告警接入该模型。

#### Scenario: 账户首次连接数超限
- **WHEN** 账户当前在线连接数超过限制且不存在对应 ACTIVE 告警状态
- **THEN** 系统创建或更新通用告警状态为 ACTIVE，并记录首次触发时间、最近触发时间、阈值、当前值和摘要

#### Scenario: 账户持续连接数超限
- **WHEN** 账户当前在线连接数仍超过限制且已存在对应 ACTIVE 告警状态
- **THEN** 系统更新最近触发时间、触发次数、阈值、当前值和摘要，并按最近通知时间决定是否重复通知

#### Scenario: 账户连接数恢复
- **WHEN** 账户此前存在 ACTIVE 连接数超限告警状态且当前在线连接数小于等于限制
- **THEN** 系统将对应通用告警状态更新为 RECOVERED，并记录恢复时间

#### Scenario: 本期不迁移其他告警
- **WHEN** 系统新增通用告警状态持久化模型
- **THEN** 服务器流量、服务器负载等现有告警本期仍保持原有状态和通知逻辑

### Requirement: AlertState 模型支持人工确认状态
`AlertState` 实体 SHALL 支持 `status` 取值 `ACKNOWLEDGED`，并新增 `acknowledgedTime`（`LocalDateTime`）和 `acknowledgedBy`（`String`）两个可空字段；`AccountOnlineLimitAlertService.shouldNotify` 在状态为 `ACKNOWLEDGED` 且距上次通知未超过节流间隔时返回 false。

#### Scenario: 连接数超限告警被人工确认后不重复通知
- **WHEN** 某账户连接数超限告警处于 `ACKNOWLEDGED` 状态，且距 `lastNotifiedTime` 未超过节流间隔
- **THEN** `shouldNotify` 返回 false，系统不发送重复通知

#### Scenario: 已确认告警在条件消失后自动恢复
- **WHEN** 某 `ACKNOWLEDGED` 告警对应的账户连接数降至限制以下
- **THEN** 系统将告警状态更新为 `RECOVERED`，`acknowledgedTime` 与 `acknowledgedBy` 字段保留不变
