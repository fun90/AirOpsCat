## Context

前一个变更 `vnstat-server-traffic` 完成后，`server_vnstat_stats` 表以 `(server_id, period_year, period_month)` 为唯一键存储月度累计流量，每次采集 UPSERT 只保留最新一条。`VnstatCollectTask` 在 `ProgrammaticTaskManager` 中已注册为每 5 分钟执行一次（`airopscat.server.vnstat.collect-minutes`，默认 5），但快照被原地更新，历史数据丢失，图表无时序数据可用。

监控图表当前展示：CPU 使用率、内存使用率、网络实时速率，均来自 `server_monitor_stats` 时序记录。vnstat 数据仅以两个汇总数字（累计上传/下载）展示在摘要卡片。

## Goals / Non-Goals

**Goals:**
- 将 vnstat 采集改为时序存储（每 5 分钟插入一条快照）
- 监控图表新增"累计流量趋势（本月）"折线图，展示 rx/tx 字节随时间的增长曲线
- 过期快照随现有清理任务一起清理（保留天数一致）

**Non-Goals:**
- 不改变采集间隔（已是 5 分钟，调度配置不变）
- 不改变摘要卡片中的累计上传/下载展示逻辑（仍取最新快照）
- 不为 vnstat 引入独立的保留天数配置

## Decisions

### 1. 存储模型：纯 INSERT 而非 UPSERT

**决定**：每次采集直接 `INSERT` 一条新记录，不再查找并更新已有记录。

**理由**：UPSERT 模型会丢弃历史快照。改为 INSERT 后每 5 分钟产生一条记录，30 天保留下约 8640 条/台服务器，数据量可控。`findLatestByServerIdAndPeriod()` 用 `ORDER BY sampledAt DESC LIMIT 1` 获取摘要卡片所需的最新值，与原 UPSERT 语义等价。

**备选**：保留 UPSERT + 新增独立 snapshot 表。否决，因为两表同步复杂度高，单表既能满足两种查询。

### 2. 图表数据接口：在现有 charts 接口中追加 vnstatPoints

**决定**：`/api/admin/server-monitors/{serverId}/charts` 响应新增 `vnstatPoints` 字段，不新建接口。

**理由**：前端已有时间窗口切换（`selectedHours`）和一次性加载图表数据的模式，两类时序数据共用同一请求可减少网络往返。字段为可选新增，不影响已有客户端。

### 3. 清理策略：复用监控数据保留天数

**决定**：`ServerMonitorStatsService.cleanupExpiredStats()` 在清理 `server_monitor_stats` 时，同步调用 `ServerVnstatStatsService.cleanupExpiredStats(cutoff)` 删除 `sampledAt < cutoff` 的 vnstat 快照。

**理由**：用户对两类监控数据通常期望相同的保留周期，避免引入额外配置项。

### 4. DB 约束变更：删除唯一约束，加时序索引

去掉 `uq_server_vnstat_period`（`server_id, period_year, period_month`），新增：
- `idx_server_vnstat_sampled_at (server_id, sampled_at)`：图表查询（按时间范围）
- `idx_server_vnstat_period_latest (server_id, period_year, period_month, sampled_at)`：摘要最新值查询

## Risks / Trade-offs

- **存量数据**：迁移脚本只删约束、加索引，现有月度汇总记录保留不动。首次迁移后旧记录的摘要查询仍正常（`ORDER BY sampledAt DESC LIMIT 1` 取到旧记录）。
- **数据量增长**：每台服务器每天 ~288 条，30 天约 8640 条。规模在 100 台以内时总量 < 100 万行，索引覆盖查询性能不受影响。超大规模时可考虑分区，但当前不在范围内。
- **vnstat 图表初始为空**：改造上线后，已有服务器需等下一个 5 分钟采集周期才有快照，图表不会立即显示历史数据（历史 UPSERT 记录只有 1 条，时序图无意义）。这是预期行为。
