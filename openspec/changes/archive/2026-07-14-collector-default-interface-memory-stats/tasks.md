## 1. 调整 collector 网络采集

- [x] 1.1 在 `src/main/resources/config/shell/01-system-init.sh` 的内嵌 collector 中，将 `read_network_totals` 改为通过 `ip route show default` 提取默认路由网卡
- [x] 1.2 修改 `read_network_totals` 只读取默认路由网卡在 `/proc/net/dev` 中的 rx bytes 与 tx bytes
- [x] 1.3 处理默认路由网卡为空或不存在于 `/proc/net/dev` 的情况，确保函数输出 `0 0` 且 collector 不失败

## 2. 调整 collector 内存采集

- [x] 2.1 在 `read_memory_stats` 中读取 `MemTotal`、`MemFree`、`Buffers`、`Cached` 与 `SReclaimable`
- [x] 2.2 使用 `MemTotal - MemFree - Buffers - Cached - SReclaimable` 计算 `memoryUsedBytes`
- [x] 2.3 保持 `memoryUsage`、`memoryUsedBytes`、`memoryTotalBytes` 输出键名和单位不变，并在 `SReclaimable` 缺失时按 0 处理

## 3. 验证

- [x] 3.1 对内嵌 collector 片段执行 shell 语法检查，确认脚本可解析
- [x] 3.2 用包含默认路由网卡、非默认网卡和内存字段的样例数据验证网络与内存计算结果
- [x] 3.3 确认本变更不需要更新 `JsonReflectionConfiguration`、数据库迁移或前端模板
