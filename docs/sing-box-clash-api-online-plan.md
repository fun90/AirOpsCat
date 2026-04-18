# sing-box Clash API 连接管理与在线 IP 改造执行计划

## 背景

当前系统已经收敛为 sing-box 单内核，`src/main/resources/config/core/sing-box.json` 中已配置 `experimental.clash_api.external_controller = 127.0.0.1:19191`，本地限速代理也已经通过 Clash API 的 `/connections` 读取连接列表。

在线账号/IP 当前仍由旧链路维护：节点侧脚本监控日志文件后调用 `/api/open/account/online/{nodeIp}`，由 `OpenController` 接收上报，再通过 `AccountOnlineIpService` 写入 `account_online_ip`。该方案依赖节点侧日志解析和额外上报脚本，链路长、状态滞后，也与 sing-box 已有 Clash API 能力重复。

本计划将在线 IP 改为由 AirOpsCat 主应用主动通过 SSH 隧道访问 sing-box Clash API 获取连接列表，并补齐 Clash API 连接管理和配置热加载能力。

## 目标

1. 增加 sing-box Clash API 连接管理能力，支持查询与断开连接。
2. 部署 sing-box 配置时优先热加载，失败后按配置回退重启。
3. 账户在线 IP 改为基于 Clash API `/connections` 主动采集。
4. 去除旧的日志监控上报 `/api/open/account/online/{nodeIp}` 方式。
5. 保持现有在线账号页面、服务器在线账号统计和账户在线筛选尽量兼容。

## 非目标

1. 不重构 `account_online_ip` 表结构，第一阶段继续用 `node_ip` 字段保存服务器 IP。
2. 不重做在线账号前端页面，只在需要连接管理操作时做最小增强。
3. 不改变现有 gRPC 流量统计链路。
4. 不删除限速代理 `02-ratelimit-agent.sh`，该脚本已基于 Clash API，不属于旧在线上报链路。

## 阶段一：补齐 Clash API 基础配置

### 配置模板

调整 `src/main/resources/config/core/sing-box.json`：

1. 保持 Clash API 默认监听本机：
   - `external_controller`: `127.0.0.1:19191`
2. 增加可选 `secret`：
   - 默认空值，依赖 SSH 隧道隔离访问面。
   - 如果后续改成 `0.0.0.0` 监听，必须设置 secret。
3. 增加 sing-box 1.8+ 推荐的 `experimental.cache_file`：
   - `enabled`: `true`
   - `path`: `/var/lib/sing-box/cache.db`
   - `cache_id`: `airopscat`

### 系统配置

新增系统配置项：

| 配置项 | 默认值 | 用途 |
| --- | --- | --- |
| `airopscat.sing-box.clash-api.host` | `127.0.0.1` | Clash API 远端监听地址 |
| `airopscat.sing-box.clash-api.port` | `19191` | Clash API 远端监听端口 |
| `airopscat.sing-box.clash-api.secret` | 空 | Clash API Bearer Token |
| `airopscat.sing-box.clash-api.timeout-seconds` | `5` | HTTP 请求超时 |
| `airopscat.sing-box.clash-api.max-retries` | `1` | Clash API 查询重试次数 |
| `airopscat.sing-box.reload.fallback-restart` | `true` | 热加载失败后是否回退重启 |

如这些配置需要在控制台可编辑，应同步更新 `SystemConfigService` 的配置分组。

## 阶段二：新增 Clash API 客户端

新增 `com.fun90.airopscat.singbox.SingBoxClashApiClient`。

### 职责

1. 通过 `SshConnection.openLocalPortForward(0, host, port)` 建立 SSH 本地端口转发。
2. 使用 Java `HttpClient` 请求本地转发端口。
3. 支持 Bearer Token：
   - `Authorization: Bearer ${secret}`
4. 支持基础连接管理接口：
   - `GET /connections`
   - `DELETE /connections/{id}`
   - `DELETE /connections`
5. 统一处理：
   - 超时
   - 重试
   - 非 2xx 响应
   - JSON 解析失败
   - SSH 隧道失败

### DTO

新增 DTO 承接 `/connections` 返回结构：

1. `SingBoxConnectionsResponse`
   - `List<SingBoxConnectionSnapshot> connections`
   - `long upload`
   - `long download`
2. `SingBoxConnectionSnapshot`
   - `String id`
   - `SingBoxConnectionMetadata metadata`
   - `Long upload`
   - `Long download`
   - `String start`
3. `SingBoxConnectionMetadata`
   - `String network`
   - `String type`
   - `String sourceIP`
   - `Integer sourcePort`
   - `String destinationIP`
   - `Integer destinationPort`
   - `String domain`
   - `String authUser`

字段应允许缺失，避免不同 sing-box 版本或协议返回结构差异导致采集失败。

## 阶段三：部署配置热加载

当前 `CoreDeploymentExecutor.deployToServer(...)` 流程为：

1. `CONFIG`
2. `RESTART`

调整为：

1. `CONFIG`
2. `RELOAD`
3. 如果 `RELOAD` 失败且 `airopscat.sing-box.reload.fallback-restart=true`，再执行 `RESTART`

### SingBoxCoreManager 调整

`SingBoxCoreManager.config(...)` 继续保持现有安全流程：

1. 创建配置目录。
2. 备份旧配置。
3. 写入新配置。
4. 执行 `/usr/bin/sing-box check -c /etc/sing-box/config.json`。
5. 校验失败时回滚旧配置。

`SingBoxCoreManager.reload(...)` 继续调用：

```bash
systemctl reload sing-box
```

如果远端 systemd unit 不支持 reload，应返回明确失败信息，由部署层决定是否回退重启。

### 结果语义

部署结果日志和异常信息应区分：

1. 配置上传失败。
2. 配置上传成功，热加载成功。
3. 配置上传成功，热加载失败，已回退重启。
4. 配置上传成功，热加载失败，未回退重启。
5. 配置上传成功，热加载失败，回退重启也失败。

## 阶段四：在线 IP 改为 Clash API 主动采集

新增服务 `AccountOnlineIpCollector` 或 `SingBoxOnlineConnectionService`。

### 采集流程

1. 查询可采集的服务器：
   - 未禁用。
   - 非外部服务器。
   - 有 SSH 凭据。
   - 存在启用的 sing-box 配置或有关联已部署节点。
2. 对每台服务器创建 SSH 连接。
3. 通过 `SingBoxClashApiClient.getConnections(...)` 获取连接列表。
4. 从连接 metadata 中解析：
   - `authUser` -> `accountNo`
   - `sourceIP` -> `clientIp`
   - 服务器 IP -> `nodeIp`
5. 调用 `AccountOnlineIpService.refreshFromConnections(serverIp, connections)` 批量 upsert。
6. 单台服务器采集失败只记录日志，不中断其他服务器。

### AccountOnlineIpService 调整

保留现有查询能力：

1. `getOnlineRecordsByAccountNo`
2. `getOnlineRecordsByNodeIp`
3. `getOnlineAccountsByServerIp`
4. `getAllOnlineRecords`
5. `countOnlineRecordsByNodeIps`
6. `getLastOnlineTimeMap`
7. `cleanupExpiredRecords`

新增方法：

```java
@Transactional
public int refreshFromConnections(String serverIp, List<SingBoxConnectionSnapshot> connections)
```

处理规则：

1. 忽略 `authUser` 为空的连接。
2. 忽略 `sourceIP` 为空的连接。
3. 同一轮内按 `accountNo + clientIp + serverIp` 去重。
4. 使用现有 `upsertOnlineStatus(...)` 维护 `lastOnlineTime`。
5. 在线判断继续使用 `lastOnlineTime > now - airopscat.online.check-minutes`。

## 阶段五：新增在线采集定时任务

在 `ProgrammaticTaskManager` 中新增任务：

| 字段 | 建议值 |
| --- | --- |
| taskKey | `account-online-refresh` |
| identity | `account-online-refresh` |
| taskName | `在线账号刷新` |
| groupKey | `monitor` |
| groupTitle | `监控与在线状态` |
| scheduleType | `interval-minutes` |
| configKey | `airopscat.account.online.refresh-minutes` |
| defaultIntervalMinutes | `1` |
| concurrentExecution | `SKIP` |

新增系统配置：

```properties
airopscat.account.online.refresh-minutes=1
```

保留在线记录清理逻辑，继续按 `airopscat.online.check-minutes * 2` 清理过期记录。

## 阶段六：连接管理 API

新增管理端接口，建议放在服务器维度：

1. `GET /api/admin/servers/{id}/connections`
   - 返回当前服务器 sing-box 连接列表。
2. `DELETE /api/admin/servers/{id}/connections/{connectionId}`
   - 断开指定连接。
3. `DELETE /api/admin/servers/{id}/connections`
   - 断开当前服务器全部连接。

可选增加账号维度接口：

1. `GET /api/admin/accounts/{accountNo}/connections`
2. `DELETE /api/admin/accounts/{accountNo}/connections`

账号维度接口可以聚合所有服务器连接，并按 `metadata.authUser` 过滤。

## 阶段七：废弃旧在线上报接口

旧接口位于 `OpenController`：

```java
POST /api/open/account/online/{nodeIp}
```

建议分两步处理：

1. 第一版软废弃：
   - 接口返回 `410 Gone` 或继续接受但记录 warning。
   - 文档标记不再使用。
   - 确认线上节点没有旧上报脚本依赖。
2. 第二版删除：
   - 删除 `OpenController.access(...)`。
   - 删除 `ClientRequest`，前提是无其他引用。
   - 删除 `AccountOnlineIpService.updateOnlineStatus(List<ClientRequest>, nodeIp)` 的旧上报入口。
   - 删除旧日志监控/上报安装脚本和文档。

注意：`src/main/resources/config/install/02-ratelimit-agent.sh` 已基于 Clash API 做限速，不属于旧在线上报链路，不应删除。

## 阶段八：前端最小适配

第一阶段无需重做前端，现有页面可继续读取 `account_online_ip`：

1. 账号列表在线状态。
2. 账号详情在线 IP。
3. 服务器列表在线账号数量。
4. 服务器在线账号弹窗。

如实现连接管理 API，可在服务器在线账号弹窗中增加：

1. 当前连接数。
2. 连接详情查看。
3. 断开单个连接。
4. 断开全部连接。

## 阶段九：测试与验证

### 单元测试

1. `SingBoxClashApiClient`
   - 正常解析 `/connections`。
   - Bearer Token header。
   - 非 2xx 响应。
   - 超时与重试。
2. `AccountOnlineIpService.refreshFromConnections(...)`
   - 空连接列表。
   - `authUser` 为空。
   - `sourceIP` 为空。
   - 同一轮去重。
   - upsert 后查询在线记录。
3. `CoreDeploymentExecutor`
   - reload 成功。
   - reload 失败并回退 restart。
   - reload 失败且不回退。
   - config 失败时不 reload。

### 手动验证

1. 在真实节点执行：

```bash
curl http://127.0.0.1:19191/connections
```

确认返回包含 `metadata.authUser` 和 `metadata.sourceIP`。

2. 部署节点后检查日志：
   - 配置校验成功。
   - 执行 `systemctl reload sing-box`。
   - 未无故重启服务。
3. 客户端连接后，账号在线 IP 页面能看到对应 IP。
4. 客户端断开后，超过 `airopscat.online.check-minutes` 自动离线。
5. 服务器列表在线账号数正确。
6. 旧 `/api/open/account/online/{nodeIp}` 不再被业务依赖。
7. gRPC 流量统计任务仍正常执行。

## 风险与处理

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| 部分 sing-box 版本 `/connections` 字段有差异 | 在线账号无法归属 | DTO 容忍缺失字段，真实节点验证各协议 |
| 某些协议 `authUser` 为空 | 无法映射账号 | 验证 vless、hysteria2、shadowtls、shadowsocks、socks；必要时调整 inbound 用户标识 |
| systemd unit 不支持 reload | 热加载失败 | 保留可配置 fallback restart |
| Clash API secret 与模板不一致 | 采集 401 | secret 统一由系统配置渲染和客户端读取 |
| 多服务器采集耗时过长 | 定时任务堆积 | `SKIP` 并发策略，服务器级失败隔离，必要时使用线程池并发采集 |
| 删除旧上报接口过早 | 线上在线状态断档 | 先软废弃，再确认无依赖后删除 |

## 建议落地顺序

1. 新增 `SingBoxClashApiClient` 和连接 DTO。
2. 新增在线连接采集服务与定时任务。
3. 修改 `AccountOnlineIpService`，支持 Clash API 批量刷新。
4. 修改部署流程为 `CONFIG -> RELOAD -> fallback RESTART`。
5. 增加服务器维度连接管理 API。
6. 软废弃旧 `/api/open/account/online/{nodeIp}`。
7. 完成真实节点验证后删除旧上报链路。

## 参考资料

1. [sing-box Clash API 配置](https://sing-box.sagernet.org/configuration/experimental/clash-api/)
2. [sing-box Experimental 配置结构](https://sing-box.sagernet.org/configuration/experimental/)
3. [sing-box Cache File 配置](https://sing-box.sagernet.org/configuration/experimental/cache-file/)
4. [sing-box 配置检查命令](https://sing-box.sagernet.org/configuration/)
