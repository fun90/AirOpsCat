## Context

`src/main/resources/config/shell/01-system-init.sh` 会在服务器初始化时写入 `/usr/local/bin/airopscat-server-monitor-collect`。该 collector 当前从 `/proc/net/dev` 汇总多块非本地网卡流量，并用 `/proc/meminfo` 的 `MemAvailable` 推导内存使用量。

本变更只调整初始化脚本内嵌 collector 的采集口径。既有输出键名、状态文件路径、CPU 采集逻辑和调用方式保持不变，避免影响后端现有解析逻辑。

## Goals / Non-Goals

**Goals:**

- 网络 rx/tx 计数只读取默认路由网卡，避免把容器、桥接、虚拟或旁路接口流量计入服务器出口速率。
- 内存 used bytes 使用 `MemTotal - MemFree - Buffers - Cached - SReclaimable` 公式，保证口径明确且可复现。
- 在老内核或精简系统未提供 `SReclaimable` 时按 0 处理，collector 仍能输出有效结果。

**Non-Goals:**

- 不改变 collector 输出字段名称、单位或状态文件格式。
- 不调整 vnstat 注册逻辑、CPU 使用率算法或后端统计入库逻辑。
- 不处理已部署服务器上的 collector 自动迁移；重新执行初始化脚本或后续部署流程时生效。

## Decisions

- 使用 `ip route show default` 获取默认路由网卡，并从第一条包含 `dev` 的默认路由中提取接口名。该方式直接反映系统默认出口选择，比在 `/proc/net/dev` 中按名称排除接口更稳定；替代方案 `ip route get 1.1.1.1` 依赖目标地址解析路径，不如用户指定的默认路由命令直观。
- `read_network_totals` 在没有默认路由网卡或 `/proc/net/dev` 找不到该接口时输出 `0 0`。这样 collector 能继续完成采集并写入状态文件，避免因临时网络异常导致整个监控采集中断。
- `read_memory_stats` 从 `/proc/meminfo` 读取 `MemTotal`、`MemFree`、`Buffers`、`Cached`、`SReclaimable`，在 `END` 阶段按公式计算 used bytes。所有字段以 KiB 转 bytes 后计算，缺失字段默认 0，最终 used 小于 0 时钳制为 0。

## Risks / Trade-offs

- [Risk] 多默认路由环境下只读取第一条默认路由网卡，可能忽略策略路由或多出口流量。→ Mitigation: 本需求明确以默认路由网卡为采集对象，后续如需多出口统计再扩展配置化策略。
- [Risk] 默认路由切换后，状态文件中的上一次 rx/tx 可能来自旧网卡，首次差值可能不具备连续性。→ Mitigation: 现有计数器回绕保护会在当前计数小于上次计数时将速率视为 0；若当前计数更大，首次数据可能偏高，下一轮恢复正常。
- [Risk] `SReclaimable` 在部分系统中不存在。→ Mitigation: awk 字段默认值为 0，不影响输出。
