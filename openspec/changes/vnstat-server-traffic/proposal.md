## Why

现有服务器监控通过远程脚本每分钟读取 `/proc/net/dev` 差值累积计算周期流量，服务器重启会导致计数器归零，需要人工校准补偿；同时每分钟一条快照对数据库造成持续写压力，周期流量查询依赖全表 SUM。改用 vnstat 守护进程原生处理计数器重置和月度聚合，消除校准机制，降低数据库压力，提升流量数据可靠性。

## What Changes

- **新增** `server_vnstat_stats` 表，按 (server_id, period_year, period_month) 唯一键存储 vnstat 月度 rx/tx 字节数
- **新增** `VnstatCollectTask` 定时任务，每小时 SSH 执行 `vnstat --json m`，自动探测出口网卡，UPSERT 当月数据
- **新增** `ServerVnstatStats` 实体、Repository、Service
- **修改** `01-system-init.sh`：新增 vnstat 安装段，注入计费日变量 `bandwidth_day` 配置 `MonthRotate`，自动探测并注册出口网卡
- **修改** `ServerMaintenanceService`：脚本执行时注入 `bandwidth_day`（取自 `server.getBandwidthDate().getDayOfMonth()`）
- **修改** `ServerMonitorStatsService`：周期流量数据源改为 `ServerVnstatStatsService`，删除差值 SUM 和校准相关逻辑
- **修改** `server-monitor/content.html`：删除校准 Modal 及相关按钮
- **删除** `server_monitor_stats` 表中 4 个网络计数器列（`networkRxBytes`、`networkTxBytes`、`networkRxIncrementBytes`、`networkTxIncrementBytes`）
- **删除** `ServerMonitorController` 的 `PUT /traffic-calibration` 接口
- **删除** `ServerMonitorTrafficCalibrationDto`
- **不改动** `server_traffic_stats` 整条线（表、entity、repository、service、TrafficStatsTask）

## Capabilities

### New Capabilities

- `vnstat-server-traffic-collection`：通过 vnstat 守护进程采集服务器网卡月度流量，按计费周期存储，作为监控模块周期流量的权威数据源

### Modified Capabilities

- `server-and-node-deployment`：`01-system-init.sh` 新增 vnstat 安装与配置步骤，服务器初始化时通过注入变量 `bandwidth_day` 设定 vnstat 计费日

## Impact

- **数据库**：新增 `server_vnstat_stats` 表；`server_monitor_stats` 删除 4 列（写入量由每分钟降至每分钟仅写 CPU/内存/速率字段）；`server_traffic_stats` 不变
- **API**：删除 `PUT /api/admin/server-monitors/{serverId}/traffic-calibration`
- **前端**：`server-monitor/content.html` 移除校准弹窗；监控摘要中 `networkRxBytes/Tx` 数据来源变更
- **运维脚本**：`01-system-init.sh` 需要在有 `bandwidth_day` 变量注入的环境下执行才能正确配置 `MonthRotate`；vnstat 未安装的服务器周期流量展示为「未采集」
- **依赖**：服务端需能 `apt-get install vnstat`（vnstat 2.x，支持 `--json`）
