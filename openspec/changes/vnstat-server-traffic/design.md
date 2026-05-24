## Context

服务器监控模块目前有两条并行的流量统计链路：
1. **sing-box 链路**（`server_traffic_stats`）：聚合用户级流量，用于账号计费，不改动。
2. **OS 网卡链路**（`server_monitor_stats`）：每分钟读取 `/proc/net/dev` 差值，SUM 得到周期流量，有计数器重置风险，依赖手动校准。

本次改动仅重构第 2 条链路，以 vnstat 守护进程替代差值 SUM 方案。

## Goals / Non-Goals

**Goals:**
- 用 vnstat 月度数据取代 `SUM(networkRxIncrementBytes)` 作为周期流量权威来源
- 消除 `monitorAdjustmentBytes` 手动校准机制
- 精简 `server_monitor_stats` 表（删除 4 个不再使用的列）
- 服务器初始化脚本自动完成 vnstat 安装与计费日配置

**Non-Goals:**
- 不改动 `server_traffic_stats` 及 sing-box 流量采集链路
- 不修改账号流量计费逻辑
- 不处理历史 `server_monitor_stats` 数据迁移（由现有 cleanup 任务自然老化）

## Decisions

### 决策 1：使用独立表 `server_vnstat_stats` 而非复用 `server_traffic_stats`

**选择**：新建 `server_vnstat_stats`，按 `(server_id, period_year, period_month)` 唯一键存储。

**理由**：`server_traffic_stats` 是 sing-box 的数据，语义不同（用户代理流量 vs OS 网卡总流量）；混用会使两条链路产生耦合，后续维护困难。独立表职责清晰，字段最小化。

**替代方案**：在 `server_traffic_stats` 新增 vnstat 字段 → 拒绝，语义污染且违反"不改动该表"原则。

---

### 决策 2：vnstat MonthRotate 在初始化脚本中配置，由管理系统注入 `bandwidth_day`

**选择**：`ServerMaintenanceService.buildScriptExecutionCommand()` 新增注入 `bandwidth_day` 变量（值为 `server.getBandwidthDate().getDayOfMonth()`，缺省 `1`）；`01-system-init.sh` 在安装 vnstat 后执行 `sed -i "s/^MonthRotate .*/MonthRotate ${bandwidth_day:-1}/" /etc/vnstat.conf`。

**理由**：复用现有变量注入机制（`server_ip`、`server_host` 同理），零额外交互；初始化时一次性完成，后续不需额外步骤。

**替代方案**：
- 放弃 MonthRotate，用 vnstat 日数据自行 SUM → 增加管理系统复杂度，放弃 vnstat 原生周期能力。
- 新增"配置 vnstat"独立操作按钮 → 增加 UI 和运维步骤，不如初始化时一步到位。

---

### 决策 3：网卡自动探测，不在 Server 实体新增 iface 字段

**选择**：采集时 SSH 执行 `ip route get 1.1.1.1 | awk '/dev/{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1);exit}}'`，fallback `eth0`；探测结果存入 `server_vnstat_stats.iface` 供审计。

**理由**：绝大多数 VPS 只有一块公网网卡，自动探测覆盖 `eth0`/`ens3`/`enp1s0` 等各种命名；避免在 Server 实体增加配置负担。

**替代方案**：Server 实体新增 `vnstatIface` 字段 → 需数据迁移和 UI 配置，对单网卡场景过度设计。

---

### 决策 4：VnstatCollectTask 独立于 ServerMonitorTask，每小时采集

**选择**：新建 `VnstatCollectTask`，通过 `ProgrammaticTaskManager` 注册，默认每小时执行，复用 `monitorTaskExecutor` 线程池。

**理由**：vnstat 数据每 5 分钟更新一次，月度总量数字小时级精度已足够；与每分钟的监控采集解耦，互不影响。

---

### 决策 5：vnstat 未安装时展示「未采集」而非 0

**选择**：`ServerVnstatStatsService.getPeriodRxBytes()/getTxBytes()` 返回 `Optional<Long>`；`ServerMonitorStatsService.getLatestSummary()` 对空值设 `vnstatAvailable=false`，前端展示「未采集」。

**理由**：0 与"确实没有流量"语义混淆，`未采集` 能提示运维人员安装 vnstat。

## Risks / Trade-offs

**[风险] 存量服务器未安装 vnstat** → 重新执行 `01-system-init.sh` 即可完成安装；过渡期间监控页面展示「未采集」不影响 sing-box 链路的账号计费。

**[风险] 不同发行版 vnstat 版本差异** → 明确要求 vnstat 2.x（`--json` 参数支持），仅支持 `apt-get install`（当前所有服务器均为 Debian/Ubuntu）。

**[风险] MonthRotate 与实际计费日不一致**（如服务器 bandwidthDate 后期修改）→ 当前不处理，需重新初始化 vnstat；在任务说明中注明此限制。

**[Trade-off] 删除 `server_monitor_stats` 的 4 列后历史累计流量数据丢失** → 这些列记录的是 OS 计数器累计值（已有误差），不作为任何业务数据使用，删除无实质损失；历史监控图表中的累计流量曲线随之消失（图表改为仅展示速率）。

## Migration Plan

1. 部署新版本前无需停机操作。
2. 新版本启动后，Flyway 自动执行 3 条 migration：
   - 新增 `server_vnstat_stats` 表
   - `ALTER server_monitor_stats` DROP 4 列
   - （`server_traffic_stats` 不变）
3. 首次 `VnstatCollectTask` 执行后，已安装 vnstat 的服务器开始有周期流量数据；未安装的显示「未采集」。
4. 对存量服务器逐台重新执行 `01-system-init.sh` 安装 vnstat（可通过管理界面运维脚本执行）。

**回滚**：回滚应用版本即可恢复旧代码；`server_monitor_stats` 被 DROP 的列无法自动恢复，但旧版本逻辑（SUM 差值）依然因列不存在而返回 0，功能降级但不崩溃。建议回滚前备份该表。

## Open Questions

- 无，所有关键决策已在 Explore 阶段与用户确认。
