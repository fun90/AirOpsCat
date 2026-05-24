## ADDED Requirements

### Requirement: 系统 SHALL 通过 vnstat 定期采集服务器 OS 网卡月度流量

系统 SHALL 每小时通过 SSH 连接各可监控服务器，执行 `vnstat -i <iface> --json m` 获取当月网卡流量，自动探测出口网卡，将 rx/tx 字节数 UPSERT 至 `server_vnstat_stats` 表，以 (server_id, period_year, period_month) 为唯一键。

#### Scenario: 正常采集并更新数据
- **WHEN** VnstatCollectTask 对一台已安装 vnstat 的服务器执行采集
- **THEN** 系统自动探测主出口网卡（`ip route get 1.1.1.1`，fallback `eth0`）
- **AND** 执行 `vnstat -i <iface> --json m` 解析当月 rx/tx（KiB × 1024 = Bytes）
- **AND** 将结果 UPSERT 至 `server_vnstat_stats`，更新 `rx_bytes`、`tx_bytes`、`iface`、`sampled_at`

#### Scenario: vnstat 未安装时跳过并标记
- **WHEN** VnstatCollectTask 对一台未安装 vnstat 的服务器执行采集
- **THEN** 系统跳过该服务器，不写入 `server_vnstat_stats`
- **AND** `server_vnstat_stats` 中该服务器当月无记录

---

### Requirement: 监控摘要 SHALL 从 vnstat 读取周期流量

服务器监控摘要接口 SHALL 从 `server_vnstat_stats` 读取当月 rx/tx 字节数作为 `networkRxBytes` 和 `networkTxBytes`，不再通过 SUM `server_monitor_stats` 差值计算。

#### Scenario: 已有 vnstat 数据时返回周期流量
- **WHEN** 调用 `GET /api/admin/server-monitors/{serverId}/summary`
- **THEN** `networkRxBytes` 和 `networkTxBytes` 取自 `server_vnstat_stats` 中当月记录

#### Scenario: 无 vnstat 数据时标记未采集
- **WHEN** 调用 `GET /api/admin/server-monitors/{serverId}/summary`
- **AND** `server_vnstat_stats` 中该服务器当月无记录
- **THEN** `networkRxBytes` 和 `networkTxBytes` 返回 `0`，且响应中 `vnstatAvailable` 为 `false`
- **AND** 前端展示「未采集」而非数字 `0`

---

### Requirement: 系统 SHALL 移除手动流量校准功能

系统 SHALL 删除 `PUT /api/admin/server-monitors/{serverId}/traffic-calibration` 接口；vnstat 原生处理计数器重置，不再需要人工补偿。

#### Scenario: 校准接口不再存在
- **WHEN** 客户端请求 `PUT /api/admin/server-monitors/{serverId}/traffic-calibration`
- **THEN** 系统返回 404

---

### Requirement: 监控图表 points SHALL 仅含速率字段，不含累计流量

监控图表数据点 SHALL 包含 CPU、内存、网络速率（`networkRxRateBytes`/`networkTxRateBytes`），不再包含 `networkRxBytes`/`networkTxBytes` 累计值。

#### Scenario: 图表数据点结构
- **WHEN** 调用 `GET /api/admin/server-monitors/{serverId}/charts`
- **THEN** 每个 point 包含 `sampleTime`、`cpuUsage`、`memoryUsage`、`memoryUsedBytes`、`memoryTotalBytes`、`networkRxRateBytes`、`networkTxRateBytes`
- **AND** point 中不包含 `networkRxBytes` 或 `networkTxBytes` 字段
