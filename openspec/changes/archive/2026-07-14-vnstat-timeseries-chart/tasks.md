## 1. 数据库迁移

- [x] 1.1 新增 Flyway migration `V2026052403__convert_server_vnstat_stats_to_timeseries.sql`：删除 `uq_server_vnstat_period` 唯一约束，新增 `idx_server_vnstat_sampled_at (server_id, sampled_at)` 和 `idx_server_vnstat_period_latest (server_id, period_year, period_month, sampled_at)` 索引

## 2. 实体与 Repository

- [x] 2.1 `ServerVnstatStats`：删除 `@UniqueConstraint`、`@DynamicUpdate`、`@PreUpdate`（改为纯插入，无需动态更新）
- [x] 2.2 `ServerVnstatStatsRepository`：`findByServerIdAndPeriod` 改名为 `findLatestByServerIdAndPeriod` 并加 `ORDER BY sampledAt DESC`；新增 `findByServerIdAndSampledAtAfter()`；新增 `deleteBySampledAtBefore()`

## 3. 服务层

- [x] 3.1 `ServerVnstatStatsService`：`upsert()` 改为 `insert()`（直接 persist 新实体）
- [x] 3.2 `ServerVnstatStatsService`：新增 `getSnapshots(Long serverId, LocalDateTime since)` 方法返回 `List<VnstatSnapshotData>`
- [x] 3.3 `ServerVnstatStatsService`：新增 `cleanupExpiredStats(LocalDateTime cutoff)` 方法
- [x] 3.4 `ServerVnstatStatsService`：新增 `VnstatSnapshotData(LocalDateTime sampledAt, long rxBytes, long txBytes)` record
- [x] 3.5 `ServerMonitorStatsService.getChartData()`：调用 `getSnapshots()` 填充 `vnstatPoints`
- [x] 3.6 `ServerMonitorStatsService.cleanupExpiredStats()`：调用 `serverVnstatStatsService.cleanupExpiredStats(cutoffTime)` 同步清理过期快照

## 4. DTO 与反射配置

- [x] 4.1 新建 `VnstatPointDto`（字段：`sampledAt / rxBytes / txBytes`）
- [x] 4.2 `ServerMonitorChartDto`：新增 `vnstatPoints` 字段（`List<VnstatPointDto>`）
- [x] 4.3 `JsonReflectionConfiguration`：注册 `VnstatPointDto`

## 5. 前端

- [x] 5.1 `server-monitor.js`：新增 `vnstatTrafficChart` 数据属性；`renderCharts()` 中在 vnstatPoints 非空时渲染累计流量趋势图；`destroyCharts()` 和 `clearRecords()` 同步处理
- [x] 5.2 `content.html`：在监控图表区域新增"累计流量趋势（本月）"卡片（`col-12 col-lg-6`，`v-if="summary && summary.vnstatAvailable"`），与网络实时速率图并排
