## Why

当前服务器监控采集脚本会汇总多块非本地网卡的收发字节，容易把 Docker、桥接、虚拟或非默认出口接口的流量计入服务器网络速率。内存使用量依赖 `MemAvailable`，与目标展示口径不一致，需要改为明确的可控公式。

## What Changes

- 修改 `01-system-init.sh` 安装的 collector 脚本，使 `read_network_totals` 通过 `ip route show default` 识别默认路由网卡，并只读取该网卡在 `/proc/net/dev` 中的 rx/tx bytes。
- 修改 collector 脚本的 `read_memory_stats`，使用 `MemTotal - MemFree - Buffers - Cached - SReclaimable` 计算 used bytes。
- 在 `read_memory_stats` 中增加 `SReclaimable` 字段读取；字段缺失时按 0 处理，保持兼容。
- 不改变 collector 输出键名、状态文件格式或调用方式。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `server-and-node-deployment`: 修改系统初始化安装的服务器监控采集脚本口径，网络速率只基于默认路由网卡，内存使用量按指定字段公式计算。

## Impact

- 影响文件：`src/main/resources/config/shell/01-system-init.sh`
- 影响系统：新安装或重新执行系统初始化脚本后的远端 `/usr/local/bin/airopscat-server-monitor-collect`
- 不新增数据库迁移、API 字段、外部依赖或前端变更。
