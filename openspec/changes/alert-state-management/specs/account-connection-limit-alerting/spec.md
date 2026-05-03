## MODIFIED Requirements

### Requirement: AlertState 模型支持人工确认状态
`AlertState` 实体 SHALL 支持 `status` 取值 `ACKNOWLEDGED`，并新增 `acknowledgedTime`（`LocalDateTime`）和 `acknowledgedBy`（`String`）两个可空字段；`AccountOnlineLimitAlertService.shouldNotify` 在状态为 `ACKNOWLEDGED` 且距上次通知未超过节流间隔时返回 false。

#### Scenario: 连接数超限告警被人工确认后不重复通知
- **WHEN** 某账户连接数超限告警处于 `ACKNOWLEDGED` 状态，且距 `lastNotifiedTime` 未超过节流间隔
- **THEN** `shouldNotify` 返回 false，系统不发送重复通知

#### Scenario: 已确认告警在条件消失后自动恢复
- **WHEN** 某 `ACKNOWLEDGED` 告警对应的账户连接数降至限制以下
- **THEN** 系统将告警状态更新为 `RECOVERED`，`acknowledgedTime` 与 `acknowledgedBy` 字段保留不变
