## Why

vnstat 按月累计采集方案落地后（`vnstat-server-traffic`），每次采集做 UPSERT 只保留一条最新月度汇总记录，导致无法绘制累计流量的时间趋势图表——图表细粒度完全依赖 `server_monitor_stats`（CPU/内存/网络速率），用户无法在监控页看到本月流量的增长曲线。改为每 5 分钟一次插入快照后，需要将时序数据接入图表展示。

## What Changes

- `server_vnstat_stats` 表：删除 `uq_server_vnstat_period` 唯一约束，改为允许多行时序快照；新增 `(server_id, sampled_at)` 和 `(server_id, period_year, period_month, sampled_at)` 索引
- `ServerVnstatStatsService`：采集逻辑从 UPSERT 改为纯 INSERT；新增 `getSnapshots()` 时序查询方法；新增 `cleanupExpiredStats()` 配合保留策略清理
- `ServerMonitorChartDto`：新增 `vnstatPoints` 字段（`List<VnstatPointDto>`）
- 新增 `VnstatPointDto`：承载 `sampledAt / rxBytes / txBytes` 时序数据
- `ServerMonitorStatsService.getChartData()`：按选定时间窗口同时返回 vnstat 快照列表
- `ServerMonitorStatsService.cleanupExpiredStats()`：同步清理过期 vnstat 快照（与监控数据保留天数一致）
- 前端图表：在监控页新增"累计流量趋势（本月）"图，当 `vnstatAvailable=true` 时在网络实时速率图旁（各 `col-lg-6`）渲染 rx/tx 累计字节折线图

## Capabilities

### New Capabilities

- `vnstat-timeseries-storage`：将 vnstat 采集从月度单条 UPSERT 改为每 5 分钟一条时序 INSERT，支持历史快照查询和过期清理
- `vnstat-traffic-chart`：监控页新增累计流量趋势图，基于 vnstat 快照时序数据，与 `selectedHours` 时间窗口联动

### Modified Capabilities

（无现有能力规格变更）

## Impact

- **数据库**：`server_vnstat_stats` 表结构变化（去唯一约束、加索引），需 Flyway 迁移
- **API**：`/api/admin/server-monitors/{serverId}/charts` 响应新增 `vnstatPoints` 字段，向后兼容
- **前端**：监控页图表区域布局调整，累计流量图按 `vnstatAvailable` 条件渲染
- **数据量**：每台服务器每天增加约 288 条 vnstat 快照记录（5分钟/次），清理策略沿用现有 `airopscat.server.monitor.retention-days`
