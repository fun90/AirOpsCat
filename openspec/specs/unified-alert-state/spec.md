# unified-alert-state Specification

## Purpose
定义跨告警类型复用的统一告警状态持久化能力，覆盖账户、服务器和域名到期告警，服务器流量阈值告警，服务器负载告警，人工确认状态，以及 `AlertState` 表结构的兼容性变更。

## Requirements

### Requirement: 到期类告警必须写入 AlertState
系统 SHALL 在每次检查账户、服务器、域名到期时，将符合到期条件的每个资源写入一条 `AlertState` 记录，`alertType` 分别为 `account-expiring`、`server-expiring`、`domain-expiring`。

#### Scenario: 账户当日到期首次检查
- **WHEN** 调度任务检测到某账户在当日到期
- **THEN** 系统在 `AlertState` 中写入或更新该账户的 `account-expiring` 告警，状态为 `ACTIVE`，并依节流规则决定是否发送 Bark 通知

#### Scenario: 账户到期告警重复检查节流
- **WHEN** 同一账户的 `account-expiring` 告警在 `lastNotifiedTime` 后未超过节流间隔（默认 23 小时）
- **THEN** 系统更新 `lastTriggeredTime` 与 `triggerCount`，不再重复发送通知

#### Scenario: 账户已不在到期范围（恢复）
- **WHEN** 调度任务检测到某账户不再满足当日到期条件（如已续期）
- **THEN** 系统将对应 `AlertState` 状态更新为 `RECOVERED`，记录 `recoveredTime`

#### Scenario: 服务器到期告警写入
- **WHEN** 调度任务检测到某服务器在当日到期
- **THEN** 系统在 `AlertState` 中写入或更新该服务器的 `server-expiring` 告警，行为与账户到期告警一致

#### Scenario: 域名到期告警写入
- **WHEN** 调度任务检测到某域名在当日到期
- **THEN** 系统在 `AlertState` 中写入或更新该域名的 `domain-expiring` 告警，行为与账户到期告警一致

### Requirement: 服务器流量阈值告警必须写入 AlertState
系统 SHALL 在每次检查服务器流量时，将超过配置阈值的服务器写入 `AlertState`，`alertType` 为 `server-traffic-threshold`，并支持触发计数与节流。

#### Scenario: 服务器流量超过阈值首次触发
- **WHEN** 调度任务检测到某服务器当前流量使用率超过配置阈值（默认 90%）
- **THEN** 系统在 `AlertState` 中写入或更新该服务器的 `server-traffic-threshold` 告警，状态为 `ACTIVE`，并依节流规则决定是否发送通知

#### Scenario: 服务器流量恢复正常
- **WHEN** 调度任务检测到某服务器当前流量使用率低于阈值
- **THEN** 系统将对应 `AlertState` 状态更新为 `RECOVERED`

### Requirement: 服务器负载告警必须写入 AlertState
系统 SHALL 将 `ServerMonitorLoadNotifier` 改造为直接操作 `AlertState`，以 `serverId + alertSubType`（`cpu`/`memory`/`traffic`）为维度写入 `AlertState`，`alertType` 为 `server-monitor-load`，删除内存 `ConcurrentHashMap` 状态。

#### Scenario: 服务器 CPU 持续高负载首次触发
- **WHEN** 调度任务检测到某服务器 CPU 使用率持续超过阈值达到配置分钟数
- **THEN** 系统在 `AlertState` 中写入 `server-monitor-load` 告警（fingerprint 含 cpu），状态为 `ACTIVE`，依节流规则决定是否发送通知

#### Scenario: 服务器 CPU 负载恢复
- **WHEN** 调度任务检测到某服务器 CPU 使用率降至阈值以下
- **THEN** 系统将对应 `AlertState` 状态更新为 `RECOVERED`，`activeAlerts` 内存 Map 中不再维护该条目

#### Scenario: 应用重启后告警状态不丢失
- **WHEN** 应用重启后调度任务首次执行
- **THEN** 系统从 `AlertState` 读取历史告警状态，`shouldNotify` 逻辑正确基于 `lastNotifiedTime` 判断，不因内存重置而重复发送通知

### Requirement: AlertState 新增 ACKNOWLEDGED 状态
系统 SHALL 支持 `AlertState.status` 取值 `ACKNOWLEDGED`，表示告警已被人工确认，且 `AlertState` 实体新增 `acknowledgedTime`（`LocalDateTime`）和 `acknowledgedBy`（`String`）字段。

#### Scenario: 告警被人工确认
- **WHEN** 运维人员通过管理界面或 API 确认某条 ACTIVE 告警
- **THEN** 系统将对应 `AlertState` 状态更新为 `ACKNOWLEDGED`，记录 `acknowledgedTime` 与 `acknowledgedBy`

#### Scenario: 已确认告警不再重复发送通知
- **WHEN** 某告警状态为 `ACKNOWLEDGED` 且距上次通知未超过节流间隔
- **THEN** 系统 `shouldNotify` 返回 false，不发送通知

#### Scenario: 已确认告警恢复后状态更新
- **WHEN** 某 `ACKNOWLEDGED` 告警的触发条件消失
- **THEN** 系统将其状态更新为 `RECOVERED`，`acknowledgedTime` 与 `acknowledgedBy` 字段保留

### Requirement: AlertState 数据库表须同步变更
系统 SHALL 通过数据库迁移脚本在 `alert_state` 表中新增 `acknowledged_time` 和 `acknowledged_by` 列，且两列均可为 NULL（向前兼容）。

#### Scenario: 迁移脚本执行
- **WHEN** 应用启动并执行数据库迁移
- **THEN** `alert_state` 表新增 `acknowledged_time DATETIME NULL` 和 `acknowledged_by VARCHAR(64) NULL` 两列，已有数据不受影响
