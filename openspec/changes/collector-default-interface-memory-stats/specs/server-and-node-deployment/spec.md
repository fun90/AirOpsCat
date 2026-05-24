## ADDED Requirements

### Requirement: 系统初始化安装的监控采集脚本必须按指定口径采集网络与内存

系统 SHALL 在 `01-system-init.sh` 安装的服务器监控 collector 中，只基于默认路由网卡采集网络 rx/tx bytes，并使用 `MemTotal - MemFree - Buffers - Cached - SReclaimable` 公式计算内存已用字节。

#### Scenario: 采集默认路由网卡流量

- **WHEN** collector 执行 `read_network_totals`
- **THEN** 系统 SHALL 通过 `ip route show default` 获取默认路由网卡
- **AND** 系统 SHALL 只读取该网卡在 `/proc/net/dev` 中的 rx bytes 与 tx bytes
- **AND** 系统 SHALL 不汇总其他非默认路由网卡的流量计数

#### Scenario: 默认路由网卡不可用

- **WHEN** collector 无法从 `ip route show default` 获取网卡，或 `/proc/net/dev` 中不存在该网卡
- **THEN** 系统 SHALL 输出 `0 0` 作为网络 rx/tx bytes
- **AND** collector SHALL 继续输出其他监控字段

#### Scenario: 按指定公式计算内存使用量

- **WHEN** collector 执行 `read_memory_stats`
- **THEN** 系统 SHALL 从 `/proc/meminfo` 读取 `MemTotal`、`MemFree`、`Buffers`、`Cached` 与 `SReclaimable`
- **AND** 系统 SHALL 按 `MemTotal - MemFree - Buffers - Cached - SReclaimable` 计算 `memoryUsedBytes`
- **AND** 系统 SHALL 基于该 `memoryUsedBytes` 与 `MemTotal` 计算 `memoryUsage`

#### Scenario: SReclaimable 字段缺失

- **WHEN** `/proc/meminfo` 未提供 `SReclaimable`
- **THEN** 系统 SHALL 将 `SReclaimable` 视为 0 参与内存使用量计算
- **AND** collector SHALL 保持输出 `memoryUsage`、`memoryUsedBytes` 与 `memoryTotalBytes`
