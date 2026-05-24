## Context

当前限速架构有两套独立 Agent 链路部署在服务器上：
1. **TC/HTB + nftables**：`connection-snapshot-agent` 每 3s 轮询 Clash API 写 `flows.json`，`ratelimit-agent` 读 `accounts.json`（KB/s）+ `flows.json` 通过 nftables mark 和 TC HTB class 对出口流量限速
2. **软断连**：`soft-ratelimit-agent` 每 3s 轮询 Clash API 统计账号速率，超速则 DELETE 强制断连

Java 侧 `RateLimitService` 负责将账号速度（KB/s）写到 `accounts.json` 并 SSH 推送到服务器。

`account-user-level-ratelimit-mbps` 已完成：`Account.downloadMbps`/`uploadMbps` 字段已落库，`NodeClient` 和 `SingBoxConfigBuilder` 已透传，部署时写入 sing-box config inbound users。

## Goals / Non-Goals

**Goals:**
- 彻底废弃 Python Agent（删除全部 shell 脚本）
- 超配账号在 sing-box config 中的 `download_mbps`/`upload_mbps` 被覆写为系统降速值
- 部署时集成 override，一次 RESTART 完成（不产生额外 RELOAD）
- 超配状态变化或账号 Mbps 变化时触发 RELOAD（无部署）
- 新增超配降速 Mbps 系统配置项（默认 1 Mbps）

**Non-Goals:**
- 不迁移 `Account.speed` 字段存量数据
- 不修改订阅生成逻辑
- 不修改 `NodeClient` 数据结构

## Decisions

### 决策 1：override 集成到部署流程（一次 RESTART）

`CoreDeploymentExecutor.buildConfig()` 在调用 `SingBoxConfigBuilder.build()` 前，先调用 `rateLimitService.buildOverrides(ctx)` 计算 override map，再将其传入 Builder。部署完成的 RESTART 同时承载限速生效，不再在 RESTART 后异步调用 `syncServer()`。

替代方案（先部署再 syncServer）：产生两次 RESTART/RELOAD，存在短暂的"无限速窗口"，实现更复杂。

### 决策 2：`RateLimitService.syncServer()` 主职责变为"推 sing-box config + RELOAD"

原流程（推 `accounts.json`）完全废弃。新流程：
1. `DeploymentDataLoader.loadForServer(serverId)` 加载该服务器的完整部署快照
2. `buildOverrides(ctx)` 计算 override map
3. `SingBoxConfigBuilder.build(ctx, ctx.nodes(), overrides)` 构建配置 JSON
4. SSH 打开连接，写入 `/etc/sing-box/config.json`
5. `CoreManagementService.RELOAD`
6. 异常时记录 error 日志，不抛出（不阻断其他服务器）

`syncAll()` 的并行结构不变，仅 `syncServer()` 内部逻辑变了。

### 决策 3：`buildOverrides()` 从 `DeploymentServerContext` 提取账号

`buildOverrides(DeploymentServerContext ctx)` 遍历 ctx 所有 `NodeDeploymentSnapshot` 中的 `NodeClient` 列表，收集 accountNo 列表。通过 `RateLimitSnapshot`（内部创建）查找 Account 和当前流量统计。调用 `accountTrafficLimitService.resolveEffectiveMbps(account, stats)` 计算每账号的有效 Mbps：
- 超配 → 系统降速 Mbps（覆盖）
- 未超配 → `account.downloadMbps`/`uploadMbps` 原始值（也需要写入，因为 Builder 否则会用 NodeClient 的值）

仅当账号有 `downloadMbps` 或 `uploadMbps` 配置（任意非空）时才加入 override map；两者均为空的账号（不限速账号）不加入 map，Builder 不写 Mbps 字段。

`CoreDeploymentExecutor` 调用 `rateLimitService.buildOverrides(ctx)` 时已有 ctx，直接传入；`syncServer()` 内部先 `loadForServer()` 再调用相同方法。

### 决策 4：`AccountTrafficLimitService` 完全移除 KB/s 体系

删除：`OVER_QUOTA_SPEED_KEY`、`DEFAULT_OVER_QUOTA_SPEED_KB`、`resolveEffectiveSpeed()`（两个重载）、`getOverQuotaSpeedKb()`、`normalizeSpeed()`。

新增：
- 常量 `OVER_QUOTA_DOWNLOAD_MBPS_KEY` / `OVER_QUOTA_UPLOAD_MBPS_KEY`
- `getOverQuotaDownloadMbps()` / `getOverQuotaUploadMbps()`：读系统配置，小于 1 时返回 1
- record `EffectiveMbpsLimit(Integer downloadMbps, Integer uploadMbps, boolean trafficOverQuotaLimited)`
- `resolveEffectiveMbps(Account, AccountTrafficStats)`：超配时返回系统降速 Mbps，否则返回账号原始 Mbps（可空）

保留：`resolveEffectiveBandwidth()`、`isOverQuota()`（超配判断逻辑不变）。

### 决策 5：不迁移 `Account.speed` 字段

`Account.speed`（KB/s）DB 列保留（避免破坏性 Flyway 迁移），Java 侧停止读写。`AccountService` 的触发条件从"speed 或 accountNo 变化"改为"downloadMbps、uploadMbps 或 accountNo 变化"。

### 决策 6：`SingBoxConfigBuilder` 新增带 override 重载，向后兼容

新增 `RateLimitOverride(Integer downloadMbps, Integer uploadMbps)` record。对 `build()` 的三个现有重载，最终汇聚点（第三个重载）新增 `Map<String, RateLimitOverride> overrides` 参数，`buildInbound()`、`buildVlessUsers()`、`buildHysteria2Users()` 依次透传。构建 user 时：accountNo 在 overrides 中则用 override 值，否则用 `NodeClient` 原始值。两者均遵守"非空且 > 0 才写入"原则。原有无 overrides 的重载以空 map 调用新重载，保持向后兼容。

## Risks / Trade-offs

- **[Risk] 最小降速 1 Mbps**：原超配降速 20 KB/s ≈ 0.16 Mbps，整数 Mbps 最小只能到 1 Mbps，降速力度减弱。→ 已知取舍，管理员可接受（1 Mbps 仍可感知降速），可通过配置项调整
- **[Risk] RELOAD 失败**：sing-box RELOAD 失败时 config 已写入磁盘但未生效，账号仍以旧速运行。→ 记录 error 日志，下次 `syncAll` 定时触发时重试
- **[Risk] 无 Agent 兜底**：废弃 Agent 后仅依赖 sing-box 内核限速。→ `syncAll` 定时任务（现有逻辑）提供重试保障，与部署流程的健壮性相同
- **[Risk] 已安装 Agent 的服务器需手动卸载**：已部署 Agent 的服务器不会自动清理。→ 文档说明需手动运行卸载脚本，或 systemctl disable/stop 对应服务

## Migration Plan

1. 删除 4 个 Agent shell 脚本（代码库清理）
2. 发布新版本，Flyway 无新增迁移脚本
3. 已安装 Agent 的服务器：管理员手动运行原卸载脚本，或 `systemctl disable --now airopscat-ratelimit airopscat-connection-snapshot airopscat-soft-ratelimit`
4. 下次节点部署或 `syncAll` 触发时，带 override 的 sing-box config 自动生效

## Open Questions

无。
