## 1. 数据库迁移

- [x] 1.1 在 `alert_state` 表新增 `acknowledged_time DATETIME NULL` 和 `acknowledged_by VARCHAR(64) NULL` 列，编写数据库迁移脚本（SQL 文件）

## 2. AlertState 实体与 Repository 扩展

- [x] 2.1 在 `AlertState` 实体中新增 `acknowledgedTime`（`LocalDateTime`）和 `acknowledgedBy`（`String`）字段，加对应 `@Column` 注解
- [x] 2.2 在 `AlertStateRepository` 中新增 `findByAlertTypeAndStatus`、`findByResourceTypeAndStatus` 等分页查询方法

## 3. 统一告警状态持久化 — 到期类

- [x] 3.1 改造 `AccountExpiringNotifier`：注入 `AlertStateRepository` 与 `BarkService`，对每个到期账户写入 `AlertState`（`alertType=account-expiring`），用 `shouldNotify`（间隔 23h）节流，资源恢复时标记 `RECOVERED`
- [x] 3.2 改造 `ServerExpiringNotifier`：同上，`alertType=server-expiring`，`resourceType=server`
- [x] 3.3 改造 `DomainExpiringNotifier`：同上，`alertType=domain-expiring`，`resourceType=domain`（若该 notifier 存在）
- [x] 3.4 确认三个 Notifier 改造后仍通过 `MonitorNotificationService` 调度，或根据实际情况调整调度入口

## 4. 统一告警状态持久化 — 服务器流量阈值

- [x] 4.1 改造 `ServerTrafficThresholdNotifier`：注入 `AlertStateRepository`，对每台超阈值服务器写入 `AlertState`（`alertType=server-traffic-threshold`），支持节流与恢复；对未超阈值服务器将 `ACTIVE` 状态改为 `RECOVERED`
- [x] 4.2 将 `ServerTrafficThresholdNotifier` 的调度从 `MonitorNotificationService` 改为 `ResourceNotificationTask` 直接调用（若需要绕过现有接口）

## 5. 统一告警状态持久化 — 服务器负载

- [x] 5.1 改造 `ServerMonitorLoadNotifier`：删除内存 `ConcurrentHashMap activeAlerts`，改为查询/写入 `AlertState`（`alertType=server-monitor-load`，`fingerprint=serverId:alertSubType`）
- [x] 5.2 确保 CPU/内存/流量三个子类型各对应独立的 `AlertState` 记录，恢复时标记 `RECOVERED`
- [x] 5.3 多线程并发写入时捕获唯一约束冲突异常，幂等处理（忽略或 merge）

## 6. AccountOnlineLimitAlertService — ACKNOWLEDGED 状态支持

- [x] 6.1 修改 `AccountOnlineLimitAlertService.shouldNotify`：状态为 `ACKNOWLEDGED` 且未超节流间隔时返回 false
- [x] 6.2 修改 `AccountTrafficOverQuotaService.shouldNotify`（同样逻辑）：`ACKNOWLEDGED` 状态豁免重复通知

## 7. 告警管理 REST API

- [x] 7.1 新建 `AlertStateVo`（单条告警视图对象）和 `AlertStatePageVo`（分页结果）DTO，包含 `AlertState` 全部字段
- [x] 7.2 新建 `AcknowledgeRequest` DTO（`acknowledgedBy: String`）
- [x] 7.3 新建 `AlertStateService`：实现分页查询（支持 `alertType`/`status`/`resourceType` 过滤）、单条查询、确认（更新状态为 `ACKNOWLEDGED`，校验当前为 `ACTIVE`）、删除逻辑
- [x] 7.4 新建 `AlertStateController`，实现 `GET /api/alert-states`、`GET /api/alert-states/{id}`、`POST /api/alert-states/{id}/acknowledge`、`DELETE /api/alert-states/{id}` 四个端点
- [x] 7.5 确认/清除非 `ACTIVE` 状态时返回 HTTP 409，不存在时返回 HTTP 404

## 8. JsonReflectionConfiguration 注册

- [x] 8.1 将 `AlertStateVo`、`AlertStatePageVo`、`AcknowledgeRequest` 注册到 `JsonReflectionConfiguration`

## 9. 前端告警管理页面

- [x] 9.1 在 `ConsolePageRegistry` 中注册告警管理页面：`moduleKey=system`、`uri=/system/alert`、`menuTitle=告警管理`
- [x] 9.2 创建模板目录 `src/main/resources/templates/system/alert/`，新建 `content.html`（页面主体，含筛选控件 + 分页表格）
- [x] 9.3 新建 `table.html`（告警列表表格片段，含状态颜色标签、操作按钮）
- [x] 9.4 新建 `modals.html`（确认告警模态框 + 清除确认对话框）
- [x] 9.5 创建 JS 文件 `src/main/resources/META-INF/resources/static/js/system/alert.js`，实现列表加载、筛选提交、确认/清除操作的 API 调用与页面刷新
- [ ] 9.6 手动验证页面：告警列表展示、筛选、翻页、确认操作、清除操作均正常

## 10. 收尾检查

- [ ] 10.1 检查所有改造后的 Notifier 在开发模式下能正确写入 `AlertState` 表
- [ ] 10.2 确认 `alert_state` 唯一约束未被触发导致异常
- [x] 10.3 确认前端页面在 GraalVM 原生镜像以外的模式下序列化正常（JsonReflectionConfiguration 在 JVM 模式下可选，但需验证）
