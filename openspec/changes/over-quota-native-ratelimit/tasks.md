## 1. 删除 Agent Shell 脚本

- [x] 1.1 删除 `src/main/resources/config/shell/02-ratelimit-agent.sh`（TC/HTB + nftables Agent 安装）
- [x] 1.2 删除 `src/main/resources/config/shell/03-ratelimit-agent-uninstall.sh`
- [x] 1.3 删除 `src/main/resources/config/shell/04-soft-ratelimit-agent.sh`（软断连 Agent 安装）
- [x] 1.4 删除 `src/main/resources/config/shell/05-soft-ratelimit-agent-uninstall.sh`

## 2. 系统配置项更新（SystemConfigService）

- [x] 2.1 删除 `airopscat.account.traffic-over-quota.speed-kb` 配置项（第 265 行）
- [x] 2.2 新增 `airopscat.account.traffic-over-quota.download-mbps`：label "超配下行降速（Mbps）"，description "账户流量超额后的下行限速值，单位 Mbps，最小 1。"，类型 `INPUT_NUMBER`，默认值 `"1"`
- [x] 2.3 新增 `airopscat.account.traffic-over-quota.upload-mbps`：label "超配上行降速（Mbps）"，description 同上（上行），默认值 `"1"`
- [x] 2.4 保留 `airopscat.ratelimit.enabled`，将 description 更新为"全局 sing-box 原生限速开关，关闭后部署和同步均不注入限速配置。"

## 3. AccountTrafficLimitService 重写

- [x] 3.1 删除常量 `OVER_QUOTA_SPEED_KEY`、`DEFAULT_OVER_QUOTA_SPEED_KB`
- [x] 3.2 删除方法 `resolveEffectiveSpeed(Account, AccountTrafficStats)`、`resolveEffectiveSpeed(Account, long, Long)`、`getOverQuotaSpeedKb()`、`normalizeSpeed()`
- [x] 3.3 新增常量 `OVER_QUOTA_DOWNLOAD_MBPS_KEY = "airopscat.account.traffic-over-quota.download-mbps"` 和 `OVER_QUOTA_UPLOAD_MBPS_KEY = "airopscat.account.traffic-over-quota.upload-mbps"`
- [x] 3.4 新增方法 `getOverQuotaDownloadMbps()`：读取对应配置，`Math.max(1, value)`
- [x] 3.5 新增方法 `getOverQuotaUploadMbps()`：同上
- [x] 3.6 新增 record `EffectiveMbpsLimit(Integer downloadMbps, Integer uploadMbps, boolean trafficOverQuotaLimited)`（替代已删除的 `EffectiveSpeedLimit`）
- [x] 3.7 新增方法 `resolveEffectiveMbps(Account account, AccountTrafficStats currentStats)`：
  - 调用 `resolveEffectiveBandwidth()` + `isOverQuota()` 判断是否超配
  - 超配：返回 `EffectiveMbpsLimit(getOverQuotaDownloadMbps(), getOverQuotaUploadMbps(), true)`
  - 未超配：返回 `EffectiveMbpsLimit(account.getDownloadMbps(), account.getUploadMbps(), false)`

## 4. SingBoxConfigBuilder 新增 override 支持

- [x] 4.1 新增 record `RateLimitOverride(Integer downloadMbps, Integer uploadMbps)`，放在 `SingBoxConfigBuilder` 内部或 `model/dto/deployment/` 下
- [x] 4.2 在第三个 `build(ServerSnapshot, List<NodeDeploymentSnapshot>, Map<Long, NodeDeploymentSnapshot>)` 重载（第 53 行）新增 `Map<String, RateLimitOverride> overrides` 参数，并传入 `buildInbound()`
- [x] 4.3 `applyNodeConfig()` 新增 `overrides` 参数并向下传入；`buildInbound(NodeDeploymentSnapshot node, Map<String, Object> inboundMap)` 同样新增 `overrides` 参数，传入 `buildVlessUsers()` 和 `buildHysteria2Users()`
- [x] 4.4 `buildVlessUsers(List<NodeClient> clients)` 新增 `overrides` 参数：构建每个 user 时，若 `overrides.get(client.accountNo())` 非 null 则用 override 的 downloadMbps/uploadMbps，否则用 `client.downloadMbps()`/`client.uploadMbps()`；均遵守"非空且 > 0 才写入"原则
- [x] 4.5 `buildHysteria2Users(List<NodeClient> clients)` 同 4.4
- [x] 4.6 第一个 `build(DeploymentServerContext ctx, List<Node> nodes)` 和第二个 `build(ServerSnapshot, List<NodeDeploymentSnapshot>)` 重载，以 `Collections.emptyMap()` 调用新的第三个重载，保持向后兼容
- [x] 4.7 确认 `RateLimitOverride` 若独立为新类需在 `JsonReflectionConfiguration` 注册（若仅作为内部 record 使用则不需要）

## 5. RateLimitService 重写

- [x] 5.1 删除常量 `REMOTE_RATE_LIMIT_DIR`、`REMOTE_RATE_LIMIT_PROFILE_PATH`
- [x] 5.2 删除方法 `buildRateLimitProfileJson()`、`extractServerAccountNos()`、`syncProfileToServer()`
- [x] 5.3 注入 `DeploymentDataLoader`（加载服务器部署快照）
- [x] 5.4 新增方法 `buildOverrides(DeploymentServerContext ctx, RateLimitSnapshot snapshot)`：
  - 遍历 `ctx` 中所有 `NodeDeploymentSnapshot.clients()` 收集唯一 accountNo
  - 从 `snapshot.accountMap()` 查找对应 Account
  - 调用 `accountTrafficLimitService.resolveEffectiveMbps(account, snapshot.currentStatsMap().get(account.getId()))` 得到 `EffectiveMbpsLimit`
  - 仅当 limit.downloadMbps() 或 limit.uploadMbps() 至少有一个非空时，将 accountNo → `RateLimitOverride` 写入 map
  - 返回 `Map<String, RateLimitOverride>`（可为空 map）
- [x] 5.5 新增 public 方法 `buildOverrides(DeploymentServerContext ctx)`：内部创建 `RateLimitSnapshot`，调用 5.4
- [x] 5.6 新增私有方法 `pushSingBoxConfig(Server server)`：
  1. `DeploymentDataLoader.loadForServer(server.getId())` 加载部署快照
  2. 调用 `buildOverrides(ctx)` 计算 override map
  3. `singBoxConfigBuilder.build(ctx, ctx.nodes(), overrides)` 构建配置 JSON
  4. `withConnection(server, ...)` SSH 写入 `/etc/sing-box/config.json`
  5. `coreManagementService.executeOperations(CORE_TYPE_SING_BOX, connection, server, RELOAD)` 热重载
  6. 异常时记录 error 日志，不抛出（不阻断其他服务器）
- [x] 5.7 `syncServer(Server server)` 改为：判断 `isEnabledSingBoxServer()` 后调用 `pushSingBoxConfig(server)`（不再创建 snapshot 和推 accounts.json）
- [x] 5.8 注入 `SingBoxConfigBuilder` 和 `CoreManagementService`（若当前未注入）

## 6. CoreDeploymentExecutor 集成 override

- [x] 6.1 `buildConfig(DeploymentServerContext ctx, List<Node> nodes)` 改为：若 `rateLimitService.isEnabled()` 为 true，则先调用 `rateLimitService.buildOverrides(ctx)` 得到 overrides，再调用 `singBoxConfigBuilder.build(ctx, nodes, overrides)`；否则以空 map 调用（或调用原无 overrides 重载）
- [x] 6.2 删除 `deployToServer()` 末尾的 `CompletableFuture.runAsync(() -> rateLimitService.syncServer(server))` 异步调用（第 150-158 行）——部署时已集成 override，不再需要单独 sync

## 7. AccountService 触发条件调整

- [x] 7.1 找到 `updateAccount()` 中"仅当 accountNo 或 speed 发生变化时才触发限速同步"的判断（第 385-389 行），将条件改为：accountNo、downloadMbps 或 uploadMbps 任意发生变化时触发 `rateLimitService.triggerAsyncSync()`
- [x] 7.2 保留 `createAccount()` 和 `deleteAccount()` 中的 `triggerAsyncSync()` 调用（不变）

## 8. AccountTrafficOverQuotaService 清理

- [x] 8.1 `triggerAlert()` 中删除 `AccountTrafficLimitService.EffectiveSpeedLimit limit` 变量及其 `resolveEffectiveSpeed()` 调用
- [x] 8.2 `buildSummary()` 中删除"当前限速: x KB/s"行（或改为"超配降速已通过 sing-box 原生限速生效"）；更新方法签名移除 `effectiveSpeed` 参数

## 9. 验证

- [x] 9.1 手动测试：将某账号设为超配状态，触发 `syncAll`，确认生成的 sing-box config 中该账号 user 的 `download_mbps`/`upload_mbps` 为降速值（默认 1）
- [x] 9.2 手动测试：未超配且有 Mbps 配置的账号，config 中限速值与账号 `downloadMbps`/`uploadMbps` 一致
- [x] 9.3 手动测试：无 Mbps 配置（两者均为空）的账号，config 中不含 `download_mbps`/`upload_mbps` 字段
- [x] 9.4 手动测试：节点重新部署后，sing-box config 已包含 override（超配账号被降速）且仅触发一次 RESTART
- [x] 9.5 手动测试：修改账号 `downloadMbps` 后，触发 `triggerAsyncSync()`，sing-box RELOAD 成功，日志无报错
- [x] 9.6 确认已无 `accounts.json` 写入行为（grep 日志无"已同步限速配置文件"输出）
