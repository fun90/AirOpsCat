## 1. 数据库变更

- [x] 1.1 执行 DDL：`ALTER TABLE account_traffic_stats ADD COLUMN bandwidth_quota BIGINT NULL COMMENT '周期流量配额（GB），null 表示使用账户基准配额';`

## 2. 实体与 DTO 更新

- [x] 2.1 在 `AccountTrafficStats` 实体中添加 `private Long bandwidthQuota;` 字段（`Long`，nullable）
- [x] 2.2 在 `AccountTrafficStatsDto` 中添加 `bandwidthQuota` 字段
- [x] 2.3 在 `AccountTrafficStatsController` 的 `updateStats()` 映射方法中将 `bandwidthQuota` 从 DTO 赋值到实体（仅在显式提供时更新）

## 3. 服务层：创建周期记录时复制配额

- [x] 3.1 修改 `AccountTrafficStatsService.saveOrUpdateTrafficStats()`：在新建 `AccountTrafficStats` 记录时，查询 `Account.bandwidth` 并赋值给 `newStats.setBandwidthQuota(account.getBandwidth())`
- [x] 3.2 确认 `saveOrUpdateTrafficStats()` 方法签名已包含 `Account` 对象或可通过 `accountId` 查询账户；如不包含，添加账户查询或调整入参

## 4. 服务层：统一有效配额查询入口

- [x] 4.1 在 `AccountTrafficStatsService` 中新增 `getEffectiveBandwidth(Long accountId)` 方法，实现：
  - 查询当前时间点所在的 `AccountTrafficStats`
  - 若记录存在且 `bandwidthQuota != null` → 返回 `bandwidthQuota`
  - 否则 → 读取 `Account.bandwidth` 作为兜底返回值（可为 null）
- [x] 4.2 在 `AccountTrafficStatsRepository` 中确认 `findByAccountIdAndCurrentTime()` 方法可复用，或补充所需查询方法

## 5. 服务层：替换使用率计算调用点

- [x] 5.1 修改 `AccountService.toDto()` 中的使用率计算：将 `account.getBandwidth()` 替换为 `accountTrafficStatsService.getEffectiveBandwidth(account.getId())`
- [x] 5.2 处理 `getEffectiveBandwidth()` 返回 null 的情况：跳过使用率计算，`usagePercentage` 保持 null 或 0

## 6. 服务层：替换订阅配额调用点

- [x] 6.1 修改 `SubscriptionService` 中读取流量配额的逻辑：将 `account.getBandwidth()` 替换为 `accountTrafficStatsService.getEffectiveBandwidth(account.getId())`
- [x] 6.2 处理返回 null 的情况（当前代码使用默认值 500 GB，保持此兜底逻辑，但将配额来源切换至 `getEffectiveBandwidth()`）

## 7. 管理 API：修改周期配额

- [x] 7.1 在 `AccountTrafficStatsController` 中新增端点 `PATCH /api/admin/traffic-stats/{id}/quota`，接受 `{ "bandwidthQuota": Long }` 请求体，更新对应记录的 `bandwidthQuota`，记录不存在时返回 404

## 8. 前端：展示与编辑周期配额

- [x] 8.1 在账户流量统计列表模板中添加 `bandwidthQuota` 列，null 显示为"—"
- [x] 8.2 在列表或详情弹窗中添加"修改周期配额"入口，调用 `PATCH /api/admin/traffic-stats/{id}/quota` 并在成功后刷新列表

## 9. 注册 JsonReflectionConfiguration

- [x] 9.1 确认 `AccountTrafficStats` 和 `AccountTrafficStatsDto` 是否已在 `JsonReflectionConfiguration` 中注册；若新字段导致反射问题，补充注册
