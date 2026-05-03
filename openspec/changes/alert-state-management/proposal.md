## Why

系统中现有五类告警通知（账户到期、服务器到期、域名到期、服务器流量阈值、服务器负载）使用 `MonitorNotifier` 的"触发即发送"模式，没有持久化告警状态，导致告警历史不可追溯、重复通知无法抑制、告警恢复无法感知；而 `AccountTrafficOverQuotaService` 和 `AccountOnlineLimitAlertService` 已率先采用 `AlertState` 实现有状态告警，但前端至今没有告警管理界面，运维人员无法查看和处理告警。

## What Changes

- **统一告警状态持久化**：将 `AccountExpiringNotifier`、`ServerExpiringNotifier`、`DomainExpiringNotifier`、`ServerTrafficThresholdNotifier`、`ServerMonitorLoadNotifier` 改造为写入 `AlertState`，对齐 `AccountTrafficOverQuotaService` 的已有模式，支持触发计数、通知节流、自动恢复。
- **新增告警管理控制台页面**：展示所有告警的当前状态与历史记录，支持按告警类型、状态（ACTIVE / RECOVERED）、资源类型筛选，分页浏览，以及手动确认（ACKNOWLEDGED）或清除告警。
- **新增告警管理 REST API**：提供分页查询、单条查询、手动确认、手动清除接口，供前端页面调用。

## Capabilities

### New Capabilities

- `unified-alert-state`：将全部现有告警类型接入 `AlertState` 持久化，统一触发/恢复/通知节流逻辑。
- `alert-console`：前端告警管理页面及对应后端 REST API，支持查询、筛选、分页、手动确认/清除。

### Modified Capabilities

- `account-connection-limit-alerting`：现有规范不变，但 `AlertState` 新增 `ACKNOWLEDGED` 状态与 `acknowledgedTime` 字段，属于数据模型扩展。

## Impact

- **模型层**：`AlertState` 新增 `acknowledgedTime`、`acknowledgedBy` 字段；新增数据库迁移脚本。
- **服务层**：`AccountExpiringNotifier`、`ServerExpiringNotifier`、`DomainExpiringNotifier`、`ServerTrafficThresholdNotifier`、`ServerMonitorLoadNotifier` 均需改造；`MonitorNotificationService` 需调整调度逻辑以适配有状态模式。
- **控制器层**：新增 `AlertStateController` REST API。
- **前端**：新增 `system/alert/` 模板目录及对应 JS，注册到 `ConsolePageRegistry`。
- **反射配置**：新增 DTO 需在 `JsonReflectionConfiguration` 中注册。
