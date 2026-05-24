## 1. 数据库迁移脚本

- [x] 1.1 新增 Flyway migration：创建 `server_vnstat_stats` 表（字段：id、server_id、iface、period_year、period_month、rx_bytes、tx_bytes、sampled_at、create_time、update_time；唯一约束 `(server_id, period_year, period_month)`）
- [x] 1.2 新增 Flyway migration：`ALTER TABLE server_monitor_stats` DROP COLUMN `network_rx_bytes`、`network_tx_bytes`、`network_rx_increment_bytes`、`network_tx_increment_bytes`

## 2. vnstat 采集层（新增）

- [x] 2.1 创建 `ServerVnstatStats` 实体（映射 `server_vnstat_stats` 表，含 `@DynamicUpdate`）
- [x] 2.2 创建 `ServerVnstatStatsRepository`（extends PanacheRepository；方法：`findByServerIdAndPeriod`、`upsert`）
- [x] 2.3 创建 `ServerVnstatStatsService`：实现 `collectFromServer(Server)`（SSH 自动探测网卡 + 执行 `vnstat --json m` + 解析 JSON + UPSERT）；实现 `getPeriodTraffic(Long serverId, int year, int month)` 返回 `Optional<VnstatPeriodTraffic>` record（rxBytes、txBytes）
- [x] 2.4 创建 `VnstatCollectTask`：通过 `ProgrammaticTaskManager` 注册，默认每小时执行，复用 `monitorTaskExecutor`，遍历可监控服务器并调用 `ServerVnstatStatsService.collectFromServer()`
- [x] 2.5 在 `JsonReflectionConfiguration` 中注册 `ServerVnstatStats`

## 3. 监控服务层改造

- [x] 3.1 `ServerMonitorStats` 实体：删除 `networkRxBytes`、`networkTxBytes`、`networkRxIncrementBytes`、`networkTxIncrementBytes` 4 个字段
- [x] 3.2 `ServerMonitorStatsRepository`：删除 `sumRxIncrement()`、`sumTxIncrement()`、`findPreviousByServerId()` 方法
- [x] 3.3 `ServerMonitorStatsService`：删除 `fillNetworkIncrement()`、`calculateRawPeriodTraffic()`、`getMonitorTrafficAdjustment()`、`calibrateCurrentPeriod()` 方法
- [x] 3.4 `ServerMonitorStatsService.collectAndSave()`：移除 `fillNetworkIncrement()` 调用，移除 `networkRxBytes`/`networkTxBytes` 赋值
- [x] 3.5 `ServerMonitorStatsService.getLatestSummary()`：`networkRxBytes`/`networkTxBytes` 改为从 `ServerVnstatStatsService.getPeriodTraffic()` 获取；无数据时设 `vnstatAvailable=false`
- [x] 3.6 `ServerMonitorStatsService.getChartData()`：移除累计流量 (`baseRx`/`baseTx`/`totals`) 逻辑，`toPointDtos()` 不再传入累计值
- [x] 3.7 删除 `ServerMonitorStatsService` 中已无引用的 `PeriodTraffic`、`RawPeriodTraffic`、`MonitorTrafficAdjustment` 内部 record

## 4. DTO 与 Controller 清理

- [x] 4.1 `ServerMonitorSummaryDto`：新增 `vnstatAvailable` 布尔字段；`networkRxBytes`/`networkTxBytes` 语义变更（注释更新）
- [x] 4.2 `ServerMonitorPointDto`：删除 `networkRxBytes`、`networkTxBytes` 字段
- [x] 4.3 `ServerMonitorController`：删除 `PUT /{serverId}/traffic-calibration` 接口方法
- [x] 4.4 删除 `ServerMonitorTrafficCalibrationDto` 类
- [x] 4.5 `JsonReflectionConfiguration`：删除 `ServerMonitorTrafficCalibrationDto` 的注册条目

## 5. 运维脚本改造

- [x] 5.1 `ServerMaintenanceService.buildScriptExecutionCommand()`：新增注入 `bandwidth_day` 变量（值为 `server.getBandwidthDate().getDayOfMonth()`；`bandwidthDate` 为 null 时默认 `1`），并加入 `export` 列表
- [x] 5.2 `01-system-init.sh`：新增 `install_vnstat()` 函数（apt 安装 vnstat、sed 配置 MonthRotate、ip route 探测网卡、vnstat --add 注册、systemctl enable+start）；在 `main()` 中调用
- [x] 5.3 `airopscat-server-monitor-collect` 脚本（内嵌于 `01-system-init.sh`）：移除 `printf "networkRxBytes=...\n"` 和 `printf "networkTxBytes=...\n"` 输出行（rx/tx 计数器读取逻辑保留，仍用于速率计算）

## 6. 前端改造

- [x] 6.1 `server-monitor/content.html`：删除 `#server-monitor-trafficCalibrationModal` 弹窗及触发按钮
- [x] 6.2 `server-monitor/content.html`：监控摘要区域的 `networkRxBytes`/`networkTxBytes` 展示处新增 `v-if="summary.vnstatAvailable"` 条件，`else` 分支展示「未采集」
- [x] 6.3 `server-monitor/content.html`（或对应 JS）：图表渲染逻辑移除 `networkRxBytes`/`networkTxBytes` 累计流量曲线
