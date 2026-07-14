## ADDED Requirements

### Requirement: 时序快照存储
每次 vnstat 采集 SHALL 向 `server_vnstat_stats` 插入一条新快照记录，而非覆盖已有记录。

#### Scenario: 正常采集写入新记录
- **WHEN** `VnstatCollectTask` 触发，SSH 执行 `vnstat --json m` 成功并解析出当月 rx/tx
- **THEN** 系统 INSERT 一条新的 `ServerVnstatStats` 记录，`sampledAt` 为当前时间，同月已有记录保持不变

#### Scenario: vnstat 未安装时跳过
- **WHEN** SSH 命令返回空输出或非零退出码
- **THEN** 系统跳过本次采集，不写入任何记录，记录 debug 日志

### Requirement: 摘要最新值查询
摘要卡片（累计上传/下载）SHALL 取当期最新一条快照的值。

#### Scenario: 当月有多条快照时返回最新
- **WHEN** 调用 `getPeriodTraffic(serverId, year, month)`
- **THEN** 返回 `sampledAt` 最大的那条记录的 `rxBytes / txBytes`

#### Scenario: 当月无数据时返回 Optional.empty
- **WHEN** 该服务器当月尚无任何 vnstat 快照
- **THEN** 返回 `Optional.empty()`，前端展示「未采集」

### Requirement: 过期快照清理
系统 SHALL 在 `ServerMonitorStatsService.cleanupExpiredStats()` 执行时，同步删除 `sampledAt` 早于保留截止时间的 vnstat 快照。

#### Scenario: 清理过期快照
- **WHEN** 监控数据清理任务执行，保留天数配置为 N 天
- **THEN** `server_vnstat_stats` 中所有 `sampledAt < now - N days` 的记录被删除
