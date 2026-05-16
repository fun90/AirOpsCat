## Context

系统有两套并存的告警机制：

1. **有状态告警**（已有）：`AccountTrafficOverQuotaService`、`AccountOnlineLimitAlertService` 使用 `AlertState` 实体持久化告警，支持触发计数、节流、恢复感知。
2. **无状态告警**（待改造）：`AccountExpiringNotifier`、`ServerExpiringNotifier`、`DomainExpiringNotifier`、`ServerTrafficThresholdNotifier`、`ServerMonitorLoadNotifier` 实现 `MonitorNotifier` 接口，每次调度仅查询符合条件的记录并直接发送 Bark 推送，不写入任何持久状态。`ServerMonitorLoadNotifier` 用内存 `ConcurrentHashMap` 做简单去重，重启后状态丢失。

前端没有告警管理界面，运维人员无法查看告警历史或手动处理告警。

## Goals / Non-Goals

**Goals:**
- 将全部现有告警类型统一写入 `AlertState`，实现告警历史可追溯。
- `AlertState` 新增 `ACKNOWLEDGED` 状态，允许人工确认告警。
- 新增前端控制台页面：展示告警列表，支持按类型/状态/资源类型筛选、分页、手动确认/清除。
- 新增 REST API 供前端调用。

**Non-Goals:**
- 不改变现有告警的触发条件、阈值配置方式。
- 不引入 WebSocket / SSE 实时推送，页面采用轮询或手动刷新。
- 不修改 Bark 通知本身的内容格式。
- 不对告警做聚合或关联分析。

## Decisions

### 1. 到期类告警（账户/服务器/域名）的 AlertState 写入策略

**问题**：到期告警是"当日触发一次"的事件型通知，不同于持续超阈值的状态型告警。

**决策**：每个到期资源对应一条 `AlertState`，`fingerprint` 为资源标识（accountNo / serverId / domain），当日首次检查写入并发送通知，同日再次检查跳过（依托现有 `shouldNotify` 节流逻辑，间隔设为 23h），次日重置（`lastNotifiedTime` 超出间隔后视为新通知周期）。资源正常后标记 `RECOVERED`。

**备选方案**：每日告警不写 `AlertState`，仍保持无状态 → 无法在前端展示历史，不采用。

### 2. `ServerMonitorLoadNotifier` 内存状态迁移

**问题**：该 notifier 用内存 `ConcurrentHashMap<alertKey, Boolean>` 防止重复告警，重启后状态丢失。

**决策**：改为写 `AlertState`，以 `serverId + alertSubType`（cpu/memory/traffic）为 `fingerprint`，`shouldNotify` 节流兼顾去重功能，删除内存 Map。调度入口改为独立方法，不再走 `MonitorNotificationService.notify()`，因为该 service 现有接口不传递 `AlertState`。

**备选方案**：保留内存 Map + 追加写 `AlertState` → 双重状态维护，容易不一致，不采用。

### 3. `MonitorNotificationService` 的角色定位

**问题**：现有 `MonitorNotificationService` 作为统一入口调度 `MonitorNotifier`，但 `MonitorNotifier` 接口只返回字符串列表，不携带资源 ID，无法与 `AlertState` 绑定。

**决策**：`MonitorNotificationService` 保留用于仍适合"批量扫描-发送"模式的到期告警；`ServerTrafficThresholdNotifier` 和 `ServerMonitorLoadNotifier` 改造为独立的 `@ApplicationScoped` 服务，直接操作 `AlertState`，绕过 `MonitorNotificationService`。调度任务 `ResourceNotificationTask` 调整对应调用点。

### 4. `AlertState` 新增字段

新增两个字段以支持人工确认：
- `acknowledgedTime: LocalDateTime`
- `acknowledgedBy: String`（确认者用户名）

状态流转：`ACTIVE → ACKNOWLEDGED → RECOVERED` 或 `ACTIVE → RECOVERED`。确认后不再发送重复通知（`shouldNotify` 增加对 `ACKNOWLEDGED` 状态的豁免）。

### 5. 前端页面架构

遵循 `docs/how-to-add-console-module.md` 规范：
- 注册到 `ConsolePageRegistry`，归属 `system` 模块组（与系统配置、定时任务并列）。
- 模板路径：`src/main/resources/templates/system/alert/`
- JS 路径：`src/main/resources/static/js/console/system/alert/index.js`
- 列表页采用 Tabler 表格 + 服务端分页，筛选通过 URL query string 传递。
- 告警详情/操作通过行内按钮触发模态框（确认/清除），操作后刷新列表。

### 6. REST API 设计

```
GET  /api/alert-states?alertType=&status=&resourceType=&page=&size=
GET  /api/alert-states/{id}
POST /api/alert-states/{id}/acknowledge
DELETE /api/alert-states/{id}
```

不提供批量删除，避免误操作。

## Risks / Trade-offs

- **到期类告警写入量**：每日到期账户/服务器数量有限，写入量可接受；但若历史数据不清理会持续增长 → 依托现有 `AlertState` 无清理机制，暂接受，后续可加定时清理。
- **`ServerMonitorLoadNotifier` 状态迁移**：改造后首次启动时内存 Map 为空，而数据库可能已有 `ACTIVE` 记录，不会重复触发（`shouldNotify` 检查 `lastNotifiedTime`），状态一致。
- **`MonitorNotifier` 接口未移除**：到期告警仍沿用该接口，与有状态告警模式不统一 → 接受，后续可逐步统一，不在本次范围。
- **并发写入**：`ServerMonitorLoadNotifier` 多线程并行检查服务器，同一服务器同一告警类型可能并发写 `AlertState` → `AlertState` 表有 `uk_alert_state_identity` 唯一约束，乐观写时异常需捕获并重试或忽略（幂等）。

## Migration Plan

1. 执行数据库迁移脚本：`AlertState` 表新增 `acknowledged_time`、`acknowledged_by` 字段。
2. 部署新代码（无下线窗口，字段可空，向前兼容）。
3. 首次运行后检查 `alert_state` 表数据写入是否正常。
4. 回滚策略：仅回滚代码，数据库字段保留（可空，不影响旧代码）。

## Open Questions

- 告警历史保留周期：目前无自动清理，是否需要在本次引入定时清理任务？（建议后续独立迭代）
- 到期告警的恢复逻辑：账户/服务器到期后是否有"恢复"状态（如续期）？ 当前设计中若次日检查不再满足到期条件则标记 RECOVERED，逻辑合理。
