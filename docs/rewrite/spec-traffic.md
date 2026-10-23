# Go 重写规格：流量采集、超额限速、在线防共享、流量阈值切换、账户到期

> 依据：`rewrite/go` 分支当前 Java 实现（只读调研），路径相对 `AirOpsCat/src/main/java/com/fun90/airopscat/`，除非另注。
> sing-box fork 路径相对 `/Users/omg/Documents/Code/VPN/sing-box/`。
> 标“未确认”的条目表示代码中无法直接确认意图或行为。
> 文中配置键均为 `system_config` 表键名，默认值取自 `service/SystemConfigService.java` 的 `item(...)` 定义（最后一个参数）；代码里 `getXxxValue(key, fallback)` 的 fallback 仅在键缺失时生效，二者不一致处单独标出。

---

## 0. 总览

```
                    ┌──────────── 中心 AirOpsCat ────────────┐
 每 15 分钟(cron)    │ TrafficStatsTask                        │
  SSH → 127.0.0.1:101│  ├ gRPC QueryStats(reset=true)          │
  (v2ray_api)        │  ├ account_traffic_stats 累加           │
                     │  ├ AccountTrafficOverQuotaService       │──→ RateLimitService.syncAll
                     │  │    (超额告警 + 触发限速全量同步)      │     (重建 config.json + reload)
                     │  ├ server_traffic_stats 累加            │
                     │  └ ServerFailoverService.checkAll       │──→ DNS A 记录切换
                     │                                         │
 每 10 秒(agent)      │ POST /api/open/guard-sync               │
  节点 agent 读本机    │  ├ AccountGuardAggregator 内存表        │
  Clash API          │  └ account_online_ip upsert             │
  /connections       │ AccountGuardEvaluationTask(10s)          │
                     │  ├ 连续超限 → 黑名单                     │
                     │  └ 连接数超限告警(Bark)                  │
                     └─────────────────────────────────────────┘
 节点：agent 把响应原样写 /run/airopscat/account-quota.json → fork 内核每 2s 重载，拒绝黑名单账户的新连接
```

---

## 1. 流量采集

### 1.1 统计接口与连接方式

- sing-box 配置模板 `src/main/resources/config/core/sing-box.json`：
  - `experimental.v2ray_api.listen = "127.0.0.1:101"`，`stats.enabled = true`，`stats.users` 由配置构建器填入本机所有托管用户名（`sing-box.json:17-22`；`singbox/SingBoxConfigBuilder.java:77-109,359-360`）。
  - `experimental.clash_api.external_controller = "127.0.0.1:19191"`，`secret` 取系统配置 `airopscat.sing-box.clash-api.secret`（`sing-box.json:8-11`；`SingBoxConfigBuilder.java:349`）。
- 中心**不直连**节点端口，均经 **SSH 本地端口转发**：
  - gRPC：`connection.openLocalPortForward(0, "127.0.0.1", 101)`，本地随机端口，plaintext gRPC（`singbox/SingBoxGrpcQueryClient.java:21-22,46,72-77`）。端口 `101` 为硬编码常量，**不可配置**。
  - 超时 `airopscat.sing-box.grpc.timeout-seconds`（默认 10，MicroProfile 配置，非 system_config），重试 `airopscat.sing-box.grpc.max-retries`（默认 1，即最多 2 次）（`SingBoxGrpcQueryClient.java:29-38`）。每次尝试都新开转发与 channel。
  - SSH 连接配置：`ScheduledSupport.buildSshConfig` → `ServerSshConfigFactory.create(server, 10000)`（超时 10000，单位未确认，推测毫秒）（`scheduler/ScheduledSupport.java:31-33`）。
- proto：`src/main/proto/traffic_stats.proto`，package `v2ray.core.app.stats.command`，服务 `StatsService.QueryStats(QueryStatsRequest{reset=2, patterns=3, regexp=4}) → QueryStatsResponse{stat=1: Stat{name=1, value=2(int64)}}`。Go 侧可直接用 sing-box/v2ray 的同名 proto，字段号需一致。

### 1.2 请求与统计名

- 请求：`reset=true`、`patterns=["user>>>.*>>>traffic>>>.*"]`、`regexp=true`（`singbox/SingBoxTrafficStatsCollector.java:29-33`）。
  - **reset=true**：读即清零，中心按“增量”处理；不在中心保存上次计数。
- 统计名格式：`user>>>{accountNo}>>>traffic>>>{uplink|downlink}`。解析：以 `>>>` 切分，`parts[1]` 为 accountNo，`parts[3]` 为方向；`parts.length<4` 或不以 `user>>>` 开头、不含 `>>>traffic>>>` 的丢弃（`SingBoxTrafficStatsCollector.java:54-76`）。
- 同一账户上下行合并为 `UserTrafficStats(uploadBytes, downloadBytes)`；**上下行都为 0 的账户被丢弃**（`:82-88`）。
- 查询异常时记录错误并返回空 map（等同“无数据”，计入 skipped 而非 failure）（`:39-42`；`scheduler/TrafficStatsTask.java` 中 `allTrafficStats.isEmpty()` → `TrafficCollectResult(0,1,0)`）。

### 1.3 采集任务流程（`scheduler/TrafficStatsTask.java`）

1. 目标服务器：`ServerRepository.findRuntimeTargetServers(today)`：`disabled=0|null`、`external=0|null`、`expireDate is null or >= today`，且至少有一个“未禁用节点 + 部署位 deployed=DEPLOYED”（`repository/ServerRepository.java:62-72`）。
2. **串行**逐台服务器：建 SSH → 采集 → 关闭（`TrafficStatsTask.java` `collectServerTrafficForServer`）。单台失败计 failure=1，不影响其他。
3. 每台服务器（`TrafficStatsTask.java:161-216`）：
   - `multiple = server.multiple ?: 1`（`:161`）。
   - **仅上行乘倍率**：`adjustedUpload = multiple × uploadBytes`（BigDecimal → `longValue()` 截断）；**下行不乘**（`:179-180`）。是否为有意设计**未确认**（Server 实体无注释，`model/entity/Server.java:66`）。
   - 服务器总量 `totalUpload += adjustedUpload; totalDownload += download` 在**查找账户之前**累加，故未知账户的流量也计入服务器总量（`:181-189`）。
   - 按 `accountNo in (...)` 批量查账户；找不到账户 → skipped。
   - 账户：`AccountTrafficStatsService.saveOrUpdateTrafficStats(accountId, userId, periodType, toDate, adjustedUpload, download)`，随后 `AccountTrafficOverQuotaService.handle(account, stats)`（`:191-199`）。
   - 服务器：若 `totalUpload>0 || totalDownload>0`，`ServerTrafficStatsService.saveOrUpdateTrafficStats(serverId, server.bandwidthDate, totalUpload, totalDownload)`（`:210-216`）。
4. 全部服务器结束后：failure>0 时 Bark 警告 “AirOpsCat 流量统计 / 流量统计收集完成，成功: x, 跳过: y, 失败: z”（`:95-98`）；然后 `ServerFailoverService.checkAll()`（`:101,110-116`）。整体异常 → Bark 错误通知（`:102-104`），且**不执行** failover 检查；无运行目标服务器时提前返回，同样不执行 failover 检查。

### 1.4 account_traffic_stats 周期划分

- 实体字段：`userId, accountId, periodStart, periodEnd, uploadBytes, downloadBytes, bandwidthQuota`。
- 查找当前周期：`accountId = ? and periodStart <= now and periodEnd > now`，取第一条（`repository/AccountTrafficStatsRepository.java:54-56`）。
- 存在 → `upload += Δup; download += Δdown`（`service/AccountTrafficStatsService.java:270-277`）。
- 不存在 → 新建（`:279-292`）：
  - `periodStart/End = TrafficPeriodUtils.resolveAccountPeriodStart/End(now, account.toDate, account.periodType)`。
  - `bandwidthQuota = account.bandwidth`（快照，可为 null）。
- 周期算法（`util/TrafficPeriodUtils.java:22-66`），**重置锚点 = 账户到期时间 `toDate`**（取其日、时分秒；YEARLY 还取月）：
  - MONTHLY（periodType 非 YEARLY 时一律按月）：本月锚点 = 本月第 `clamp(toDate.day, 1, 本月天数)` 日 + `toDate.time`；`now < 本月锚点` → `[上月锚点, 本月锚点)`，否则 `[本月锚点, 下月锚点)`。
  - YEARLY：本年锚点 = 本年 `toDate.month` 月的锚点日；同理前后年。
  - `toDate == null` → 每月 1 日 00:00 / 每年 1 月 1 日 00:00。
  - 注意 clamp 导致例如锚点 31 日在 2 月取 28/29 日，下月又回到 31 日。
- 并发：两个请求同时首建可能产生重复周期记录；表上无唯一约束（未确认，实体未见 UniqueConstraint）。Java 依赖采集任务串行 + `SKIP` 并发策略。
- `bandwidthQuota` 单位 **GB（1024³ 字节）**，见 `service/AccountTrafficLimitService.java:50-52`；`account.bandwidth` 同为 GB（`AccountTrafficStatsService.java:191-205` 注释）。
- 有效配额：`currentStats.bandwidthQuota ?: account.bandwidth`；null 或 ≤0 视为不限量（`AccountTrafficLimitService.java:36-48`）。
- 清理：`account-traffic-cleanup` 任务删除 `period_end < now - retention-days`（默认 180 天，批 1000）（`AccountTrafficStatsService.java:295-330`；`AccountTrafficStatsRepository.java:98-110`）。

### 1.5 server_traffic_stats

- 周期：`TrafficPeriodUtils.resolveServerPeriodStart/End(now, server.bandwidthDate)`，按月，**锚点与参考时间都先截到当天 00:00**（`TrafficPeriodUtils.java:14-20,68-70`）。
- `getOrCreateCurrentPeriodStats` 按 `(serverId, periodStart, periodEnd)` 精确查找，无则新建（上下行 0、`monitorUpload/DownloadAdjustmentBytes = 0`）（`service/ServerTrafficStatsService.java:86-105`），然后累加（`:31-37`）。
- 字段 `monitorUpload/DownloadAdjustmentBytes` 由人工校准写入（`:107-113`），采集不修改。
- 用途：`server-traffic-notify` 流量阈值提醒读取本表（见 §4.6）。**ServerFailoverService 不读本表**，读 vnstat（见 §4.3）。
- 清理：`server-traffic-cleanup`，默认保留 180 天。

---

## 2. 超额限速

### 2.1 字段含义与单位（`model/entity/Account.java:44-65`）

| 字段 | 含义 | 单位 | 现状 |
|---|---|---|---|
| `bandwidth` | 账户周期流量基准配额 | GB | 新周期时复制到 `bandwidthQuota` |
| `downloadMbps` / `uploadMbps` | 账户自身原生限速，写入 sing-box user 的 `download_mbps` / `upload_mbps` | Mbps | 生效 |
| `speed` | 注释“账号限速（KB/s）” | KB/s | **业务代码不再使用**：仅 `AccountRepository.findActiveRateLimitedAccounts` 引用，且该方法无调用者（`repository/AccountRepository.java:31-33`）。openspec `account-traffic-over-quota-handling` 中按 `speed` 比较的场景已与代码不符 |
| `maxConnections` | 跨节点总连接数上限 | 条 | guard 判定 + 告警 |
| `maxIps` | 跨节点总去重 IP 上限；同时作为单节点兜底下发 `max_ips` | 个 | guard 判定 + 静态下发 |

### 2.2 判定

- `usedBytes = stats.upload + stats.download`（上行已含服务器倍率）。
- 超额：`effectiveBandwidth != null && > 0 && usedBytes >= effectiveBandwidth × 1024³`（`AccountTrafficLimitService.java:43-48`）。
- 每次采集、每个有流量的账户都会判定（`TrafficStatsTask.java:199`）。没有新流量的账户不会进入 `handle`，因此**不会被判定恢复**（恢复只发生在该账户再次产生流量时，或周期翻转后新周期记录首次写入时）。

### 2.3 限速速率来源

- 超额时：`downloadMbps = max(1, airopscat.account.traffic-over-quota.download-mbps)`、`uploadMbps = max(1, ...upload-mbps)`，默认均为 1（`AccountTrafficLimitService.java:11-23,28-30`；`SystemConfigService.java:265-266`）。**直接覆盖**账户自身限速，不取较小值（即使账户自身更低也被改为超额值）。
- 未超额：账户自身 `downloadMbps/uploadMbps`（可为 null）（`:31-33`）。
- 账户详情 `trafficOverQuotaLimited` 来自同一函数（`service/AccountService.java:308-309`）。

### 2.4 下发方式：重建整份配置 + reload（不使用运行时 API）

- 全局开关 `airopscat.ratelimit.enabled`（默认 **false**）（`service/ratelimit/RateLimitService.java:98-100`；`SystemConfigService.java:267`）：
  - 关闭时：`syncAll` 直接跳过（`:150-153`）；正常部署也**不注入**超额覆盖（`service/deployment/CoreDeploymentExecutor.java:113-114`），但账户自身 `download/upload_mbps` 仍会作为 `NodeClient` 字段写入（`SingBoxConfigBuilder.java:177-186`；`service/deployment/DeploymentDataLoader.java:238-239`）。
- `buildOverrides(ctx)`：对该服务器上所有托管 client（按 email=accountNo），在活跃账户（`disabled=0 and (toDate is null or toDate>now)`）中查当前周期统计，计算 `EffectiveMbpsLimit`；任一方向非 null 即放入 override（`RateLimitService.java:180-211,257-274`）。
- `putRateLimitFields`：有 override 用 override，否则用 client 原值；`>0` 才写 `download_mbps` / `upload_mbps`；`max_ips>0` 写 `max_ips`（`SingBoxConfigBuilder.java:177-191`）。vless 与 hysteria2 users 均调用。
- 推送：`deploymentDataLoader.loadForServer` → `singBoxConfigBuilder.build(ctx, placements, overrides)` → SSH 执行 `CoreOperation.CONFIG`（写 `/etc/sing-box/config.json`）+ `CoreOperation.RELOAD`（`RateLimitService.java:213-236`）。具体 reload 命令在 `CoreManagementService`/`SingBoxCoreManager`，本规格未展开（未确认是 `systemctl reload` 还是 restart）。
- 触发：`AccountTrafficOverQuotaService.handle` **每次判定为超额都调用** `triggerAsyncSync()`，恢复时也调用（`service/AccountTrafficOverQuotaService.java:50-56,101`）。
  - `triggerAsyncSync` 若在事务中，注册 afterCompletion，**提交成功后**才调度（`RateLimitService.java:284-305`）。
  - 合并：`asyncSyncRunning/Pending` 与 `syncAllRunning/Pending` 两层合并，同时只有一轮全量同步，新请求合并到下一轮（`:113-146,307-361`）。
  - 一轮全量同步对所有运行目标服务器并发推送（线程池 `monitorTaskExecutor`）（`:164-175`）。
  - 结果：只要有任一账户处于超额，**每个 15 分钟采集周期都会对全部服务器重推配置并 reload**（现状行为，Go 重写建议改为“状态变化才同步”，见 §8）。
- 恢复条件：当前周期 `usedBytes < quota`（例如管理员调高 `bandwidthQuota`）或进入新周期（新记录用量从 0 开始）。恢复后同样触发全量同步恢复账户原值。

### 2.5 超额告警（AlertState）

- `alertType="account-traffic-over-quota"`，`resourceType="account"`，`resourceId=accountId`，`resourceKey=accountNo`，`fingerprint = accountNo + ":" + periodStart`（`LocalDateTime.toString()`，如 `A10001:2026-10-05T12:00`）；兼容旧 fingerprint=`accountNo`（仅当其 `lastTriggeredTime` 落在当前周期内时迁移）（`AccountTrafficOverQuotaService.java:127-141,170-189`）。
- 触发：若状态 RECOVERED → 重新 ACTIVE（重置 first/recovered/ack）；`lastTriggeredTime=now`、`triggerCount+1`、`lastValue=已用 GB`、`thresholdValue=配额 GB`、summary（`:59-88`）。
- 通知节流：ACKNOWLEDGED 不通知；否则 `lastNotifiedTime == null || lastNotifiedTime + interval <= now` 才发；interval 复用 `airopscat.account.connection-limit.alert.min-interval-minutes`（默认 60）（`:25,143-149`）。发送成功才写 `lastNotifiedTime`。
- 消息：标题 `AirOpsCat 账户流量超额`，level `timeSensitive`；正文：
  ```
  账户: {accountNo}（{remark}）
  当前用量: {x.xx} GB
  有效配额: {n} GB | 不限量
  超配降速已通过 sing-box 原生限速生效
  ```
  （`:151-156`）。注意：即使 `ratelimit.enabled=false`，正文仍声称已生效（现状）。
- 恢复：ACTIVE/ACKNOWLEDGED → RECOVERED，写 recoveredTime 与恢复 summary，**不发通知**（`:90-103`）。

---

## 3. 在线与防共享

### 3.1 采集方式：节点 guard agent 主动上报（非中心拉取）

- 安装脚本：`src/main/resources/config/shell/06-guard-agent.sh`（profile 仓库 `profile/airopscat/shell/` 中**不存在**对应 guard 脚本，编号 06 空缺；脚本内注释写“由 08-guard-agent.sh 安装”，与文件名不符）。
- 注入变量：`server_ip`、`airopscat_domain`、`airopscat_api_token`（`service/maintenance/ServerMaintenanceService.java:207-214`）。可选：`guard_sync_interval`（默认 10s）、`guard_include_connection_refs`（默认 false）、`singbox_config`（默认 `/etc/sing-box/config.json`）（`06-guard-agent.sh:19-31`）。
- 安装产物：`/usr/local/bin/airopscat-guard-agent.py`、`/etc/systemd/system/airopscat-guard-agent.service`（`Restart=always`，环境变量含 Token 明文）、配额文件 `/run/airopscat/account-quota.json`（`:29-33,322-347`）。依赖仅 python3。
- agent 循环（每 `SYNC_INTERVAL_SECONDS`）：
  1. 读本机 sing-box 配置的 `experimental.clash_api.external_controller/secret`（默认 `127.0.0.1:19191`）（`:177-189`）。
  2. `GET /connections`（`Authorization: Bearer {secret}`），HTTP 长连接复用。
  3. 按 `metadata.authUser` 聚合（空则跳过）：`connections` 计数、`ips` 为去重 `metadata.sourceIP`；同时按 `(accountNo, nodeTag)` 聚合在线 IP，`nodeTag = metadata.type` 中 `/` 后部分（如 `vless/node_12` → `node_12`；无 `/` 为空串）（`:191-251`）。Node tag 规则为 `"node_" + nodeId`（`model/entity/Node.java:102`）。
  4. `POST https://{airopscat_domain}/api/open/guard-sync`，body gzip 压缩，头 `Content-Type: application/json`、`Content-Encoding: gzip`、`Token: {api_token}`（`:285-304,325`）。中心由 `config/GzipRequestReaderInterceptor.java` 解压。
  5. 2xx 且响应含非零 `schemaVersion` → 将**响应原文**原子写入（tmp + rename，0644）配额文件（`:254-263,306-310`）。
  6. 任何失败：跳过本轮，**保留旧文件**（由内核 TTL 判定过期后 fail-open）。
- 中心侧 `SingBoxClashApiClient` / `SingBoxOnlineConnectionService` 仅供管理界面手动查看 / 关闭连接使用（`controller/ServerController.java:397-437`），**不参与**防共享自动流程。其地址/端口/secret/超时/重试来自 `airopscat.sing-box.clash-api.*`（默认 `127.0.0.1` / `19191` / 空 / 5s / 1）（`singbox/SingBoxClashApiClient.java:121-139`），同样经 SSH 本地端口转发（`:62`）。API：`GET /connections`、`DELETE /connections/{id}`、`DELETE /connections`（`:41-51`）。

### 3.2 guard-sync 接口

- `POST /api/open/guard-sync`（`controller/OpenController.java:146-184`），JSON，`/api/open/*` 在 Quarkus 权限中为 permit（`application.properties:55-56`），鉴权由接口自身完成：
  - 头 `Token` 必须等于 `airopscat.api.token`；**若该配置为空则不校验**（`:150-155`）。配置默认值是占位字符串（非空），所以默认需要 Token。失败 → `401 {"error":"无效的 Token"}`。
  - `nodeIp` 为空 → `400 {"error":"缺少 nodeIp"}`（`:156-160`）。
- 请求（`model/dto/guard/GuardSyncRequest.java` 等）：
  ```json
  {
    "nodeIp": "203.0.113.10",
    "generatedAtEpochSeconds": 1778640000,
    "accounts": [
      {"accountNo": "A10001", "connections": 12, "ips": ["198.51.100.1", "198.51.100.2"]}
    ],
    "onlineAccountIps": [
      {"accountNo": "A10001", "nodeTag": "node_12", "clientIps": ["198.51.100.1"],
       "connections": [{"clientIp": "198.51.100.1", "connectionId": "uuid", "start": "2026-10-18T10:00:00Z"}]}
    ]
  }
  ```
  `onlineAccountIps[].connections` 仅在 agent 开启 `GUARD_INCLUDE_CONNECTION_REFS` 时出现。`generatedAtEpochSeconds` 中心不使用。
- 响应（`GuardSyncResponse.java`；全局 Jackson `NON_NULL`，`config/JacksonConfig.java:24`）：
  ```json
  {"schemaVersion": 1, "generatedAtEpochSeconds": 1778640005, "ttlSeconds": 30,
   "blockedAccounts": {"A10001": {"reason": "connections", "total": 12, "limit": 10}}}
  ```
  - `reason ∈ {"connections","ips"}`；只含**本次上报中出现**且已在黑名单的账户（`service/guard/AccountGuardAggregator.java:113-121`）。
  - 聚合或在线刷新异常时只记日志，仍返回 200（黑名单为空）（`OpenController.java:162-176`）。
  - 响应格式即 fork 内核读取的配额文件格式（`sing-box/common/accountquota/guard.go:8-15,58-63`），**字段名与 schemaVersion=1 是跨仓库契约**。

### 3.3 跨节点聚合算法（内存，`AccountGuardAggregator`）

- 状态（进程内存，重启丢失）：
  - `table: accountNo → nodeIp → NodeStat{connections, ips, reportedAtEpochSeconds}`；
  - `consecutiveExceedCounts: accountNo → int`；
  - `blockedAccounts: accountNo → GuardBlockedEntry`（`:34-48`）。
- 上报（guard-sync 热路径，`reportAndEvaluateWithStats`，`:64-124`）：
  - 对每个上报账户：`table[accountNo][nodeIp] = {max(connections,0), ips, now}`。
  - 本节点本次未上报的账户：删除 `table[*][nodeIp]`（视为该节点已无连接）。
  - 只读取黑名单返回，**不推进连续计数**。
- 统计（`calculateStats`，`:173-191`）：跳过 `now - reportedAt > ttl` 的节点格；`totalConnections = Σ connections`，`totalIps = |∪ ips|`，`activeNodeCount`。
- 判定（`evaluateAccount`，`:212-223`）：先判 `maxConnections>0 && totalConnections > maxConnections` → `connections`；再判 `maxIps>0 && totalIps > maxIps` → `ips`。严格大于。
- 中心采样（`AccountGuardEvaluationTask` 每 10s，`evaluateTrackedAccounts`，`:130-171`）：
  - 对 `table ∪ consecutive ∪ blocked` 中的账户：账户不存在 → 清除计数与黑名单；未超限 → 立即清零并移出黑名单；超限 → 计数 `min(required, c+1)`，`≥ required` 加入黑名单（覆盖为最新 entry）。
  - `required = max(1, airopscat.account.connection-limit.alert.consecutive-times)`，默认 3（`:258-261`）。
  - 注意：判定不检查账户 `disabled/toDate`（只要账户存在）。
- TTL：`max(5, airopscat.account.guard.ttl-seconds)`，默认 30（`:253-256`；迁移 `V2026071401__increase_account_guard_ttl.sql` 把 15 改为 30）。
- 清理：`AccountGuardEvictTask` 每 30s 删除过期格及空账户条目（`:225-240`；`scheduler/AccountGuardEvictTask.java:23`）。

### 3.4 节点侧执行（fork 内核）：拒绝新连接，不踢旧连接

- `accountquota.Guard` 随 Box 注册（`sing-box/box.go:244-246`），每 2s `stat` 配额文件，mtime/size 变化才重新解析（`common/accountquota/guard.go:43-49,127-169`）；路径可用环境变量 `AIROPSCAT_ACCOUNT_QUOTA_FILE` 覆盖。
- `Blocked(accountNo)`：文件缺失 / 解析失败 / schema≠1 / `ttlSeconds<=0` / `now - 文件mtime > ttlSeconds` → **fail-open 放行**（`guard.go:182-200`）。新鲜度用本地 mtime 而非 `generatedAt`。
- 路由入口 `CheckAndTrack`：先查跨节点黑名单（命中 → 拒绝，原因 `global {reason}`），再占用单节点 `max_ips/max_connections` 名额（`common/connlimit/inbound.go:25-47`；`route/connlimit.go:27-30`）。拒绝即静默关闭**新连接**。
- **中心与 agent 都不主动踢掉已存在的连接**；黑名单账户的已有连接保持到自然断开。管理界面可手动 `DELETE /connections[/id]`（§3.1）。
- 单节点兜底：配置 user 级 `max_ips`（只写 maxIps，未写 max_connections）（`SingBoxConfigBuilder.java:187-191`；`sing-box/option/inbound_user.go:6-8`）。

### 3.5 account_online_ip

- 表结构（`model/entity/AccountOnlineIp.java`）：`account_no, client_ip, connection_id, node_ip, node_id, node_tag, last_online_time, session_start_time, create_time, update_time`；唯一键 `(account_no, node_ip, connection_id)`；索引 `(account_no,last_online_time)`、`(node_ip,last_online_time)`、`(node_id,last_online_time)`、`(last_online_time)`。
- 写入：guard-sync 中 `onlineAccountIps != null` 时 `refreshFromGuardAccountIps(nodeIp, ...)`（`OpenController.java:169-176`；`service/AccountOnlineIpService.java:148-212`）：
  - 过滤不存在的 accountNo；`nodeTag` 映射到该服务器 IP 上已部署、未禁用、`type=0` 的节点得到 `node_id`（`repository/NodeRepository.java:20-28`），映射不到则 `node_id=null`。
  - 默认（无 connection refs）：每个 `(accountNo, clientIp, nodeIp, nodeTag)` 一行，`connection_id = "guard-ip|{accountNo}|{clientIp}|{nodeIp}|{nodeTag}"`（`:199-203,507-517`）。
  - 有 connection refs：每条连接一行，`connection_id` 用 Clash 连接 id（空则回退上式），`session_start_time` 解析 `start`（OffsetDateTime/Instant，失败用 now）（`:214-244,519-546`）。
  - MySQL `INSERT ... ON DUPLICATE KEY UPDATE`：更新 `client_ip/node_id/node_tag/last_online_time/update_time`；`session_start_time` 仅当旧 `last_online_time <= now - check-minutes`（视为离线后重新上线）时重置（`repository/AccountOnlineIpRepository.java:188-225`）。每次 upsert 独立事务 `REQUIRES_NEW`，死锁/锁超时重试 3 次（退避 50ms×n）（`AccountOnlineIpService.java:258-322`）。
- “在线”定义：`last_online_time > now - airopscat.online.check-minutes`（默认 10 分钟）（`:556-558`）。下线不删除行，只是不再续期。
- 清理：
  - 定时 `account-online-ip-cleanup`：删除 `last_online_time < now - retention-hours`（默认 8 小时），任务间隔也是该小时数（`:328-351`；`ProgrammaticTaskManager.java:278-290`）。
  - 手动接口：删除超过 `2 × check-minutes` 的记录（`:357-384`；`controller/AccountController.java:399-400`）。
- 用途：管理界面在线列表、节点在线计数、`node-online-account-stats` 日采样（每 5 分钟）。**不参与防共享判定**（判定只用内存聚合表）。

### 3.6 连接数超限告警（`service/AccountOnlineLimitAlertService.java`）

- 由 `AccountGuardEvaluationTask` 在每次中心采样后调用 `checkAndNotifyFromGuard(statsByAccountNo)`（`scheduler/AccountGuardEvaluationTask.java:26-42`）。
- 开关 `airopscat.account.connection-limit.alert.enabled`（默认 true）（`:245-247`）。
- **只告警连接数**（`maxConnections`），**IP 超限只入黑名单、不告警**（`:97-112`）。
- 账户不存在 / 已禁用 / 已过期 / 无 maxConnections → 恢复该账号所有 ACTIVE 告警并清计数（`:75-79,164-174,249-256`）。
- 计数：本服务**自己维护**一份 `consecutiveExceedCounts`（与聚合器的独立，阈值同键），`connections > limit` 时 +1，达到阈值触发；未超限清零并恢复（`:114-123`）。
- AlertState：`alertType="account-connection-limit"`，`resourceType="account"`，`fingerprint=accountNo`（无周期）；触发时非 ACTIVE（含 ACKNOWLEDGED）都会被**重置为 ACTIVE**（`:140-144`）——与超额告警不同，ACKNOWLEDGED 无法持续压制通知（现状）。
- 节流：同 §2.5，`min-interval-minutes` 默认 60（`:199-206`）。
- 通知：标题 `AirOpsCat 账户连接数超限`（timeSensitive），正文：
  ```
  账户: {accountNo}（{remark}）
  当前连接数: n
  连接数限制: m
  去重客户端 IP 数: k
  活跃节点数: j
  数据来源: guard 实时上报
  ```
  恢复时仅当 `recovery-notify-enabled=true`（默认 false）才发 `AirOpsCat 账户连接数恢复`（info 级）（`:182-197,222-232`）。

---

## 4. 流量阈值切换（`service/ServerFailoverService.java`）

### 4.1 触发时机

- `TrafficStatsTask` 每轮采集成功后调用 `checkAll()`（`TrafficStatsTask.java:101`）。
- `DomainDnsScheduleTask` 每分钟（`@Scheduled(cron="0 * * * * ?")`），若存在任一部署位配置了 `cheapStart` 也调用 `checkAll()`（`scheduler/DomainDnsScheduleTask.java:27-48`；`ServerFailoverService.java:216-218`）。
- `ReentrantLock.tryLock` 防重入，正在执行则本轮跳过（`:98-102`）。
- **无持久化切换状态**，每轮按当前使用率与时间重算（类注释 `:37-41`）；唯一持久痕迹是 DNS 记录的 `failoverHomeContent`。

### 4.2 管理对象（域名引用）

- 收集（`collectRefs`，`:313-328`）：
  - 部署位 `NodeServer.accessHostId`（非 PENDING_DELETE）→ 该 DNS 记录归属该部署位；
  - 启用的账户订阅定制域名绑定（`enabled=1 and domainDnsRecordId not null`），归属其 `nodeServerIds` 的第一个部署位（需属于同一 node）。
- 可切换记录（`isSwitchable`，`:331-336`）：`type=A`、`status=SYNCED`、有 `externalRecordId`、未启用时段计划 `scheduleEnabled`。
- 跳过规则（`plan`，`:229-267`，跳过原因各 warn 一次）：
  - 多个引用方不属于同一节点同一服务器；
  - 节点不存在、非 PROXY 类型、已禁用；
  - 归属部署位不在该节点“已部署”候选中；
  - 记录当前 content 不是该节点任一已部署服务器 IP（如经中转）；
  - `failoverHomeContent == null` 且当前 content ≠ 归属服务器 IP（视为人工指定）。

### 4.3 使用率口径

- `usage = vnstat 当前周期 (rx + tx) / (server.bandwidth × 1024³)`；`bandwidth` null/≤0 → 0（不限量）（`:380-400`；`service/ServerMonitorStatsService.java:139-142`）。
- 即切换使用 **vnstat 网卡流量**，不使用 `server_traffic_stats`；而 §4.6 的阈值提醒使用 `server_traffic_stats`。代码注释称“与流量告警同一口径”（`:379`），与实际不符。
- 阈值：`normalizeThreshold(airopscat.server.monitor.alert.traffic-threshold)`，支持 0–1 或 0–100，≤0 视为 1.0（`service/expiration/ServerTrafficThresholdNotifier.java:186-193`）。默认值：配置定义 **0.85**（`SystemConfigService.java:312`），代码 fallback 0.9。

### 4.4 目标选择（`selectTarget`，`:358-377`）

候选 = 该节点已部署部署位，按 `priority asc (null 最后), id asc` 排序（`repository/NodeServerRepository.java:17-19`）。

1. 便宜时段优先：第一个 `cheapActive(now)`（`Asia/Shanghai` 当前时间落在 `[cheapStart, cheapEnd]`，`TimeWindow.contains`）且 `usage < threshold` 的候选；
2. 否则归属服务器 `usage < threshold` → 归属；
3. 否则其他候选中第一个 `usage < threshold`；
4. 全部超阈值 → null：保持当前解析，Bark 警告 “域名 X：节点 Y 的所有部署服务器流量均已超过阈值，保持当前解析”（每条记录只发一次，条件消失后可再发；内存集合，重启重置）（`:271-278,308`）。

### 4.5 执行与回切

- 目标 IP 等于当前 content：不推送；若目标是归属则清 `failoverHomeContent`（`:283-288`）。
- 否则生成 Switch，reason：目标在便宜时段且非归属 → `CHEAP_WINDOW`；归属 `usage>=threshold` → `THRESHOLD`；否则 `RESET`（切回归属）（`:290-292`）。
- `apply`（`:189-213`）：新事务内重读记录，状态或 content 与计划时不一致则放弃；`DomainDnsPushService.pushRecordContent(domainId, recordId, ip)` 调用 DNS 服务商批量变更接口修改 A 记录（`service/DomainDnsPushService.java:92-111`）；切离归属时 `failoverHomeContent = 归属IP`，切回时置 null；写系统日志 `SERVER_FAILOVER`（detail 含 reason/nodeId/homeServerId/toServerId/fromContent/toContent/homeUsage/toUsage/success）。推送失败回滚并独立记录 ERROR 日志，下一轮重试。
- 成功后 Bark 警告：`域名 {fullName}（节点 {nodeName}）：{旧IP} → {服务器名或IP} {使用率%}（{reason}）`。
- 回切：无专门逻辑——周期重置后 vnstat 使用率下降、归属 `< threshold`，下一轮自然选回归属（`RESET`）。
- 不修改任何部署配置、订阅内容或节点数据，只改 DNS A 记录 content。
- 时段计划记录（`scheduleEnabled=true`）走另一条路径：`DomainDnsRecordService` 在应用时段计划时调用 `resolveScheduled(record, want)`（`service/DomainDnsRecordService.java:308`；`ServerFailoverService.java:128-166`）：`scheduleContentIn` 与 `scheduleContentOut` 必须同属唯一一个节点的已部署服务器；`want` 的服务器超阈值则换为同节点按优先级第一个未超阈值候选（不含便宜时段规则），全部超阈值返回 `want` 并告警一次。

### 4.6 服务器流量阈值提醒（相关，非切换）

- 任务 `server-traffic-notify`，cron 默认 `0 10 10 * * ?`（每天 10:10）（`SystemConfigService.java:309`）。
- 对 `disabled=0` 且 `bandwidth>0` 的服务器：`(server_traffic_stats.upload + download) / (bandwidth × 1024³) >= threshold` → AlertState `server-traffic-threshold`，节流 `airopscat.server.traffic.alert.min-interval-minutes`（默认 60），否则恢复（不通知）（`ServerTrafficThresholdNotifier.java:58-136`）。注意本处节流**不检查 ACKNOWLEDGED**，且不论发送成败都写 `lastNotifiedTime`。

---

## 5. 账户到期处理（`scheduler/AccountExpirationTask.java:35-89`）

- 任务 `account-expiration`，cron 默认 `0 0 5 * * ?`（每天 05:00，Asia/Shanghai）。
- 查询 `disabled = 0 and toDate is not null and toDate < now`（`repository/AccountRepository.java:72-74`）。
- 通过账户标签找关联节点（账户 tag ∩ 节点 tag，`repository/TagRepository.java:112-123`）；无节点则直接结束（**此时账户不会被禁用**，现状）。
- `NodeDeploymentService.deployNodesForcibly(nodes)` 强制重新部署：部署数据只加载 `disabled=0 and (toDate is null or toDate > now)` 的账户（`TagRepository.java:62-70`），故过期账户从 sing-box users 中移除。
- 然后 `disableExpiredAccounts(ids)`：`disabled=1, updateTime=now`（`AccountRepository.java:80-83`），失败只记日志。
- 通知：`AirOpsCat 定时任务执行情况 / 成功: x 失败: y`（info 级）；异常 → `AirOpsCat 定时任务执行失败`（active 级）。
- 时效：账户在 `toDate` 后到次日 05:00 之间仍在节点配置中（除非期间有其他部署/限速同步重建配置——这些路径同样过滤过期账户，因此可能提前生效）。guard、在线告警对过期账户的处理见 §3.3、§3.6。
- 到期提醒（`resource-expiration-notify`，默认 `0 0 10 * * ?`）属于另一规格，不在此展开。

---

## 6. 定时任务一览与依赖

注册方式：`ProgrammaticTaskManager` 启动时用 Quarkus `Scheduler` 编程注册，时区 `Asia/Shanghai`，并发策略均为 `SKIP`；支持手动执行、暂停（**暂停状态仅内存**，重启恢复调度）、恢复（`scheduler/ProgrammaticTaskManager.java:96-170`）。
- `interval-minutes` 类型转换为 `0 */N * * * ?`；`interval-hours` 为 `0 0 */N * * ?`（`:209-223`）。N 不整除 60/24 时间隔不均匀，N≥60（分钟）时 cron 非法（现状风险）。
- `cron` 类型直接读配置值。

| 任务 | 注册 | 周期（默认） | 配置键 | 作用 |
|---|---|---|---|---|
| traffic-stats-collect | 编程 | `0 */15 * * * ?` | `airopscat.traffic.stats.cron` | §1 采集 → §2 超额 → §4 切换 |
| account-expiration | 编程 | `0 0 5 * * ?` | `airopscat.account.expiration.cron` | §5 |
| account-online-ip-cleanup | 编程 | 每 8 小时（`0 0 */8 * * ?`） | `airopscat.account.online.history.retention-hours`（同时是保留时长） | §3.5 |
| node-online-account-stats | 编程 | 每 5 分钟 | `airopscat.node.online-account.stats.sample-minutes` | 读 account_online_ip 采样 |
| vnstat-traffic-collect | 编程 | 每 5 分钟 | `airopscat.server.vnstat.collect-minutes` | 为 §4 提供使用率 |
| server-traffic-notify | 编程 | `0 10 10 * * ?` | `airopscat.server.traffic.notify.cron` | §4.6 |
| account-traffic-cleanup | 编程 | `0 40 3 * * ?` | `airopscat.account.traffic.cleanup.cron` | 删 180 天前周期 |
| server-traffic-cleanup | 编程 | `0 0 4 * * ?` | `airopscat.server.traffic.cleanup.cron` | 同上 |
| AccountGuardEvaluationTask | `@Scheduled(every="10s")`，`@Transactional` | 10s | 不可配置、不可暂停 | §3.3 黑名单 + §3.6 告警 |
| AccountGuardEvictTask | `@Scheduled(every="30s")` | 30s | 不可配置 | 回收过期聚合格 |
| DomainDnsScheduleTask | `@Scheduled(cron="0 * * * * ?")` | 每分钟 | 不可配置 | DNS 时段计划；有便宜时段时调 `checkAll` |
| guard agent（节点） | systemd | 10s | `guard_sync_interval` | §3.1 |

依赖关系：
- 超额判定依赖采集写入的周期记录；限速同步异步执行，在采集事务提交后开始。
- 切换依赖 vnstat 采集（5 分钟）而非流量采集结果，但由流量采集任务（及每分钟便宜时段检查）触发。
- 黑名单依赖 agent 上报新鲜度：`ttl(30s) ≈ 3 × 上报间隔(10s)`，中心采样 10s × 连续 2 次 ⇒ 超限后约 10–30s 进入黑名单，节点再经 ≤10s（agent）+ ≤2s（内核重载）生效。
- 中心重启：内存聚合表、计数、黑名单清空；agent 拿到空黑名单覆盖文件 → 所有账户放行，直至重新累计。

---

## 7. Bark 推送格式（`service/BarkService.java`）

- 配置：`airopscat.bark.url`、`airopscat.bark.device-key`（二者均有值才发送，否则返回 false）、`encrypt-enabled`、`encrypt-key`、`encrypt-iv`、`default-group`（默认 `AirOpsCat`）、`default-sound`（默认 `healthnotification`）、`default-icon`（`:309-336`；`SystemConfigService.java:232-239`）。
- 级别快捷方法（`:63-85`）：`sendWarningNotification` → `level=timeSensitive`；`sendErrorNotification` → `level=active`；`sendInfoNotification` → 无 level；三者都带默认 group/sound/icon。返回 bool 表示 2xx。
- 明文模式（`:153-176`）：`POST {barkUrl}/{deviceKey}`，`Content-Type: application/json`，body 为 `BarkNotificationDto` 的 JSON（`NON_NULL`，`config/JacksonConfig.java:24`），字段：
  ```json
  {"device_key": "<key>", "title": "...", "body": "...", "icon": "...", "sound": "healthnotification",
   "url": null, "group": "AirOpsCat", "autoCopy": null, "copy": null, "level": "timeSensitive",
   "badge": null, "isArchive": null, "category": null, "threadId": null, "priority": null,
   "timeout": null, "actionable": null, "actions": null}
  ```
  （null 字段实际不输出；`device_key` 为 `@JsonProperty`，其余为 camelCase，`model/dto/BarkNotificationDto.java`）。
- 加密模式（`encrypt-enabled=true` 或 key/iv 任一非空即启用，`:237-241`）：key 必须 32 字节、iv 16 字节（UTF-8 长度），AES-256-CBC/PKCS5Padding 加密上述 JSON，Base64；`POST {barkUrl}/{deviceKey}`，`application/x-www-form-urlencoded`，表单 `ciphertext=<base64>&iv=<iv 明文>`（`:178-208,243-267`）。
- GET 模式 `sendNotificationGet`：`{barkUrl}/{deviceKey}/{title}/{body}?icon=&sound=&url=&group=&autoCopy=1&copy=&level=&badge=&isArchive=1`（`:269-307`），本规格涉及的流程不使用。
- 失败：异常与非 2xx 只记日志，不重试。

---

## 8. 与 openspec 的差异及重写注意事项

与 `openspec/specs` 不一致（以代码为准）：
- `account-traffic-over-quota-handling`：要求同步更新 `accounts.json`（Agent 路径），代码已无此路径，只重推 sing-box 配置；账户详情按 `speed` 比较的场景已失效，实际为 Mbps 覆盖。
- `account-connection-limit-alerting`：描述“在线刷新任务后基于在线窗口统计连接数”“清理最大在线 IP 数”，代码已改为 guard 实时内存聚合，并重新引入 `max_ips` 作为防共享主判据。

现状缺陷 / 风险（重写时决定是否保留，均为代码事实）：

> 处理决定（2026-10-19，见 `scope.md` 决策 21）：2 改为上下行都乘倍率；6、7 保持现状；其余按各条建议修复（9 统一 vnstat 口径、阈值 0.85；10 固定周期、不提供暂停；11 Token 必填）。

1. `reset=true` 读即清零：采集后若 DB 写入失败，该段流量丢失；节点 sing-box 重启/reload 也会丢失上次采集后的计数（reload 是否清零统计未确认）。Go 版可考虑 `reset=false` + 中心保存上次值做差，或保留现状。
2. 服务器倍率只作用于上行（`TrafficStatsTask.java:179-180`），意图未确认。
3. 只要有账户持续超额，每个采集周期都对全部服务器重推配置并 reload；建议改为“超额状态变化时”同步（openspec 原意即“状态变化”）。
4. 无新流量的账户不会被判定恢复。
5. 账户到期依赖每日 05:00 任务，最长约 24 小时宽限；无关联节点时账户不被禁用。
6. guard 状态全内存，单实例假设；多实例部署需外置（Go 版若多实例需重新设计）。
7. 黑名单只拒绝新连接，不踢存量连接。
8. 连接数告警与聚合器各维护一份连续计数；IP 超限无告警；ACKNOWLEDGED 对连接数告警无持续压制作用。
9. 切换使用 vnstat，阈值提醒使用 server_traffic_stats，口径不同；阈值默认值配置 0.85 与代码 fallback 0.9 不一致。
10. 任务暂停状态不持久化；interval 转 cron 的方式对非整除值不均匀。
11. guard-sync 在 `airopscat.api.token` 为空时不鉴权；systemd unit 中 Token 以环境变量明文保存（文件权限依 systemd 默认）。

跨仓库契约（改动需同步 sing-box fork 与安装脚本）：
- v2ray_api 监听 `127.0.0.1:101`、统计名格式、`stats.users` 列表；
- Clash API `127.0.0.1:19191` + secret，`/connections` 中 `metadata.authUser/sourceIP/type`；
- 配额文件路径 `/run/airopscat/account-quota.json` 与 schemaVersion=1 字段；
- user 级 `download_mbps/upload_mbps/max_ips`；
- inbound tag `node_{id}`。
