# 节点多服务器部署与流量自动切换方案

## 1. 目标

用“逻辑节点 + 部署位”替换现有节点组（`node.node_group`），简化节点交互：

1. 同一个节点配置可部署到多台服务器，配置只维护一份。
2. 可指定哪些服务器（部署位）暴露到订阅。
3. 服务器流量达到阈值时，通过修改 DNS 记录把节点自动切换到其他服务器；服务器流量重置后自动切回。

分两期实施：

- **第一期**：数据模型、数据迁移、部署、订阅、前端交互。上线后节点组下线。
- **第二期**：流量阈值自动切换与切回、切换日志、通知。

已确认的决策：

| 事项 | 结论 |
|------|------|
| 模型 | 一次到位改为逻辑节点 + 部署位，分两期 |
| 切换阈值 | 复用系统配置“流量告警阈值” `airopscat.server.monitor.alert.traffic-threshold` |
| 切回时机 | 服务器 `bandwidthDate`（流量重置日期）到达、进入新流量周期后自动切回 |
| 端口 | 部署位端口固定与节点一致，不支持按部署位覆盖 |

## 2. 现状

| 能力 | 现有实现 | 本方案的处理 |
|------|----------|--------------|
| 节点组 | `node.node_group` 字符串；同组节点类型相同、不同服务器，保存时由 `NodeGroupService` 对齐协议/端口/入站/出站 | 删除 |
| 组内用户合并 | `DeploymentDataLoader.buildAssociatedNodeClientsMap` 按组合并账号 | 删除，一个逻辑节点的账号天然共用 |
| 组展开部署 | `NodeGroupService.expandWithRelatedGroups` | 改为按节点展开到部署位 |
| 服务器流量周期 | `Server.bandwidth`（GB）、`Server.bandwidthDate`、`TrafficPeriodUtils.resolveServerPeriodStart/End`、`ServerTrafficStats` | 第二期复用 |
| 流量采集 | `traffic-stats-collect` 任务，默认 `0 */15 * * * ?` | 第二期在采集后触发切换检查 |
| 流量告警 | `ServerTrafficThresholdNotifier`，阈值默认 `0.85`，支持 0-1 或 0-100 写法 | 第二期复用阈值读取与归一化 |
| DNS 记录 | `DomainDnsRecord`，Cloudflare `batchChangeRecords`；`DomainDnsScheduleTask` 每分钟按时段改内容并整域 push | 第二期参照该模式改记录内容 |
| 账号级订阅域名 | `AccountNodeSubscriptionDomainBinding`（账号 + 节点 → DNS 记录） | 保留，优先级最高 |

## 3. 数据模型（第一期）

### 3.1 `node`：逻辑节点

保留：`id`、`protocol`、`core_type`、`type`、`port`、`inbound`、`out_id`、`rule`、`level`、`disabled`、`name`、`no`、`remark`、`node_tag` 关联。

新增：

| 字段 | 说明 |
|------|------|
| `dns_record_id` | 可空，关联 `domain_dns_record.id`。绑定后订阅只输出一条，host 为该记录域名 |

移除（迁移完成后删除列）：`server_id`、`access_host_id`、`node_group`、`deployed`。部署状态下沉到部署位。

### 3.2 `node_server`：部署位（新表）

| 字段 | 说明 |
|------|------|
| `id` | 主键 |
| `node_id` | 逻辑节点 |
| `server_id` | 服务器 |
| `access_host_id` | 可空，接入域名（原 `node.access_host_id`） |
| `exposed` | 1/0。未绑定 DNS 时表示是否出现在订阅；绑定 DNS 时表示是否参与切换 |
| `priority` | 越小越优先。决定订阅排序、默认生效部署位、切换顺序 |
| `enabled` | 1/0。0 表示不部署到该服务器（原 `node.disabled` 迁移到这里） |
| `name` | 可空，订阅显示名覆盖；为空时用“节点名 + 服务器名” |
| `deployed` | 0 待部署 / 1 已部署 / 2 待删除，沿用 `NodeDeploymentStatus` |
| `legacy_node_id` | 迁移用，记录原 `node.id`，第一期稳定后删除 |
| `create_time`、`update_time` | — |

约束：唯一键 `(node_id, server_id)`；索引 `server_id`。端口取 `node.port`，同一服务器上的端口冲突校验从“按节点”改为“按部署位所在服务器”。

### 3.3 部署记录

- `node_deployment`：新增 `node_server_id` 并改为唯一键，保留 `node_id`、`server_id` 便于查询；删除 `node_group` 列。
- `node_deployment_history`：新增 `node_server_id`，删除 `node_group` 列。
- 快照 `NodeDeploymentVersionSnapshotDto` 增加 `nodeServerId`，去掉 `nodeGroup`。

### 3.4 不变的部分

- 入站 tag 仍为 `node_<node.id>`。同一节点在不同服务器上 tag 相同，因为不在同一份配置里，所以不冲突。
- `account_online_ip`、`node_online_account_daily_stats` 继续按 `node_id` 统计。需要按服务器区分时用 `account_online_ip.node_ip`。
- 账号与节点的可见关系仍走 `node_tag`。

## 4. 第一期设计

### 4.1 节点保存

- 请求体用 `placements[]`（`serverId`、`accessHostId`、`exposed`、`priority`、`enabled`、`name`）替换 `serverId`、`accessHostId`、`nodeGroup`。
- 节点配置字段变更：所有未待删除的部署位标为待部署。
- 部署位增加：新部署位为待部署。部署位删除：标为待删除，部署时从该服务器移除入站后再删行。
- 删除节点：所有部署位标为待删除，最后一个部署位删除后再删节点行。
- 校验：至少一个启用的部署位；同节点不能重复选同一服务器；每个启用部署位所在服务器的端口不冲突；绑定的 DNS 记录类型只能是 `A` 或 `CNAME`，未开启 Cloudflare 代理，未开启时段计划（`scheduleEnabled`），且未被其他节点绑定。

### 4.2 部署

- 部署单元从节点改为部署位。`deployNodes(nodeIds)` 展开为这些节点的待部署部署位，再按 `server_id` 分组调用现有 `processNodesByServer` 流程。
- `DeploymentDataLoader` 按服务器加载“该服务器上的部署位 → 逻辑节点”。快照的 `serverIp` 取部署位所在服务器。原先全局按 `nodeId` 建的快照 map，改为在每个 `DeploymentServerContext` 内按 `nodeId` 建（同一服务器上 `nodeId` 唯一）。
- 账号列表直接取逻辑节点的 `buildNodeClientsMap`，删除 `buildAssociatedNodeClientsMap`、`mergeClients` 的组合并逻辑。
- **出站 `out_id` 与路由规则 `outbound_node_id`**：仍指向逻辑落地节点。落地节点有多个部署位时，出站地址取“生效部署位”：第一期为启用、已部署、`priority` 最小的部署位，第二期为 `active_node_server_id`。落地节点生效部署位变化时，引用它的代理节点部署位标为待部署。
- 部署后写 `node_deployment`（按 `node_server_id`）；节点列表的部署状态取各部署位的汇总（有任一待部署即显示待部署）。

### 4.3 订阅输出

`SubscriptionService.loadAvailableSubscriptionNodes` 改为：

1. 按 `node_tag` 取账号可用逻辑节点（不变），过滤 `disabled`、非代理类型。
2. 取这些节点下启用、已部署的部署位及其 `node_deployment` 快照。
3. 按以下规则生成订阅条目：

| 情况 | 订阅条目 | host |
|------|----------|------|
| 有账号级 `AccountNodeSubscriptionDomainBinding` | 1 条，用生效部署位的快照 | 绑定记录的域名 |
| 节点绑定了 `dns_record_id` | 1 条，用生效部署位的快照；至少要有一个 `exposed` 部署位 | `dns_record` 的 `full_name` |
| 其他 | 每个 `exposed=1` 的部署位 1 条，按 `priority` 排序 | 部署位接入域名 → 服务器 host → 服务器 IP（沿用 `resolveEffectiveHost`） |

多条时名称取部署位 `name`，为空时用“节点名 + 服务器名”。同一节点各部署位的配置一致，所以单条输出时取哪个部署位的快照，只影响 host 以外的字段来源，结果相同。

### 4.4 前端交互

- 节点表单：删除节点组 Tom Select 和“组配置锁定”逻辑（`isNodeGroupConfigLocked`、`nodeGroupSyncMessage` 等），服务器选择改为“部署服务器”表格，列为服务器、接入域名、暴露、优先级、启用、部署状态（只读）、操作。另加可选的“订阅 DNS 记录”下拉。
- 节点列表：一行一个逻辑节点；“节点组”列和排序替换为“部署服务器”列，用 Tabler badge 展示各部署位，并标出暴露、待部署状态；保留按服务器筛选，语义改为“部署位包含该服务器”。
- 删除接口 `GET /api/admin/nodes/group-options`、`GET /api/admin/nodes/group-config`。
- 新增和修改的请求/响应 DTO（部署位请求、部署位 DTO、节点 DTO 新字段）注册到 `JsonReflectionConfiguration`。

### 4.5 需删除的代码

`NodeGroupService` 整体，`NodeRepository` 中的 `findByNodeGroup*`、`findNodeGroupCandidateNodes`，`NodeService`、`NodeConverter`、`NodeObfuscator`、`SubscriptionService` 中的 `nodeGroup` 读写，`docs/node-associated-design.md`（上线后改为指向本文）。

## 5. 数据迁移（第一期）

项目使用 `schema-management.strategy=update`，加列、建表会自动完成，但合并数据、删列不会。迁移 SQL 放在 `src/main/resources/db/migration/`，沿用 `V<日期序号>__<描述>.sql` 命名，维护窗口内手工执行。

### 5.1 合并规则

- 未分组节点：`id` 不变，生成 1 个部署位。
- 同名 `node_group` 的节点：取最小 `id` 作为逻辑节点（主节点），每个原节点行生成 1 个部署位，`legacy_node_id` 记录原 id。非主节点的 id 映射到主节点 id。
- 部署位 `name`：原节点名与主节点名不同时写入原名，保证订阅显示名不变。
- 部署位 `enabled`：取原节点 `1 - disabled`；`exposed` 默认 1；`priority` 按原 `no`、`id` 排序。

### 5.2 迁移前检查（只读 SQL，输出给人工确认）

1. 同组内 `rule`、`level`、`disabled`、`remark` 不一致的组，合并后以主节点为准。
2. 同组内 `node_tag` 的并集与差集。合并后取并集，会扩大部分账号的可见范围。
3. `account_node_subscription_domain_binding` 中同一账号绑定了同组多个节点的记录，合并后违反唯一键 `(account_id, node_id)`，需选定保留哪条。
4. 节点数、组数、部署位预计数。

### 5.3 迁移步骤

1. 备份数据库。
2. 部署第一期版本前，执行迁移 SQL：
   1. 创建 `node_server`，按 5.1 生成部署位，`deployed` 沿用原节点状态。
   2. 建临时映射表 `node_id_map(old_id, new_id)`。
   3. 按映射更新：`node_tag`（去重）、`account_node_subscription_domain_binding`（按 5.2 第 3 项的结果）、`node.out_id`、`route_rule.outbound_node_id`、`account_online_ip.node_id`、`node_online_account_daily_stats`（同日合并：`latest`、`peak` 求和，`unique` 取最大，作为近似值）。
   4. `node_deployment`、`node_deployment_history` 按 `legacy_node_id` 回填 `node_server_id`，再按映射更新 `node_id`。
   5. 把原分组节点对应的部署位标为待部署，删除非主节点行。
3. 启动新版本，对涉及分组节点的服务器执行强制部署。原非主节点的入站 tag 从 `node_<旧id>` 变为 `node_<主id>`，必须重新部署。在此之前，这些节点暂不出现在订阅中；未分组节点不受影响。
4. 校验：部署位总数等于迁移前节点数；抽查订阅条目数和名称与迁移前一致。
5. 稳定后执行第二份 SQL，删除 `node.server_id`、`access_host_id`、`node_group`、`deployed`，`node_deployment(_history).node_group` 和 `node_server.legacy_node_id`。

回滚：第 5 步之前可以恢复备份并回退版本；第 5 步之后只能恢复备份。

## 6. 第二期：流量自动切换

### 6.1 新增字段

| 位置 | 字段 | 说明 |
|------|------|------|
| `node` | `failover_enabled` | 1/0，仅代理类型且绑定了 `dns_record_id` 的节点可开启 |
| `node` | `active_node_server_id` | 当前 DNS 指向的部署位，为空时按第一期规则取默认 |
| 新表 `node_failover_log` | `node_id`、`from_node_server_id`、`to_node_server_id`、`reason`（`THRESHOLD` / `RESET` / `MANUAL`）、`from_usage`、`to_usage`、`dns_content`、`success`、`message`、`create_time` | 切换审计，清理策略参照 `node-deployment-history-cleanup` |

落地节点（`type=1`）不参与自动切换：出站用 IP 直连，不走 DNS。

### 6.2 选择规则

每轮对每个 `failover_enabled=1` 的节点计算目标部署位：

```
候选 = 启用、已部署、exposed=1 的部署位，按 priority 升序
目标 = 候选中第一个“所在服务器当期使用率 < 阈值”的部署位
若没有满足的 → 目标 = 当前 active（不切换），发告警
若目标 ≠ 当前 active → 切换
```

- 使用率 = 当前周期 `ServerTrafficStats` 上下行之和 / (`bandwidth` GB)；周期由 `TrafficPeriodUtils.resolveServerPeriodStart(now, bandwidthDate)` 决定。未设置 `bandwidth` 的服务器视为不限量，始终满足。
- 阈值读取 `airopscat.server.monitor.alert.traffic-threshold`，与 `ServerTrafficThresholdNotifier` 共用同一个归一化方法（把其中的 `normalizeThreshold` 提为共享方法，不复制）。
- **切回**不需要单独逻辑：当期流量只增不减，优先级更高的服务器一旦超阈值，本周期内会一直被跳过；到达其 `bandwidthDate` 进入新周期后使用率回到低位，它重新成为第一个满足条件的候选，下一轮自动切回，日志 `reason=RESET`。
- 特例：新周期尚未采集时没有 `ServerTrafficStats`，使用率按 0 计算，符合“已重置”的事实。
- 人工调大 `bandwidth`、改高阈值或校准流量后，使用率可能低于阈值，同样会触发切回，这是预期行为。

### 6.3 执行

- 触发：在 `TrafficStatsTask.collectUserTrafficStats` 采集结束后调用切换检查，不新增 Cron。响应延迟上限约等于采集周期（默认 15 分钟）加 DNS TTL。
- DNS 内容：记录类型 `A` 写部署位服务器 `ip`；`CNAME` 写部署位的有效 host（接入域名 → 服务器 host）。
- 推送：设置记录 `content`，状态改为 `PENDING_UPDATE`，然后推送。推送失败时整个事务回滚，`active_node_server_id` 不变，写失败日志，下一轮重试。
- 推送范围：现有 `DomainDnsPushService.push(domainId)` 会推送整个域名的所有待推送记录，可能把用户尚未确认的本地修改一并推上去（时段计划任务已有同样行为）。建议把 `push` 拆出 `push(domainId, records)`，切换只推送这一条记录；推送后的整域 pull 是否会覆盖其他待推送记录，需在实现时核实。
- 通知：切换成功或所有候选都超阈值时，通过 `BarkService` 推送，内容含节点、来源与目标服务器、使用率。
- 手动切换：节点详情提供“切换到…”。自动切换开启时，下一轮会按规则重新计算，所以界面上提示“手动切换前请关闭自动切换”，不额外做锁定功能。

### 6.4 前置条件与风险

- DNS 记录 TTL 应设为低值（如 60 秒），且关闭 Cloudflare 代理；已建立的连接会继续使用旧服务器，客户端 DNS 缓存会进一步延迟生效。
- 一台服务器上挂多个节点时，切走一个节点不一定能让服务器降到阈值以下；阈值按服务器判断，切换按节点执行，界面上需要说明。
- 使用 TLS 证书的协议（如 hysteria2、shadowtls），证书必须在所有部署位服务器上都覆盖该 DNS 域名，否则切换后握手失败。第二期在开启自动切换时校验并提示。
- 账号级订阅域名绑定指向的不是节点 DNS 记录时，该账号不受自动切换影响。

## 7. 验证

| 期 | 检查 |
|----|------|
| 第一期 | `./mvnw test`；为订阅条目生成规则（4.3 三种情况）补单元测试；在迁移库副本上执行 5.2 检查和 5.3 迁移，核对第 4 步校验；开发模式下验证节点增删改、部署位增删、单节点部署、服务器部署、订阅输出 |
| 第二期 | 为 6.2 选择规则补单元测试，覆盖：正常、超阈值切走、全部超阈值、新周期切回、未设 `bandwidth`；用测试域名验证真实 Cloudflare 推送和失败回滚 |

## 8. 待实现时确认

1. `DomainDnsPullService.pull` 是否会覆盖本地其他待推送记录（影响 6.3 推送范围）。第二期确认。
2. 前端是否已有可复用的 DNS 记录选择组件（账号订阅域名绑定处）。已确认：复用 `/api/admin/domain-dns-records` + `createRemoteSearchConfig` 的 Tom Select。
3. `CoreDeploymentExecutor` 删除入站的路径，是否能按部署位只删除某台服务器上的入站。已确认：部署按服务器重建完整配置，待删除部署位不进入快照即从该服务器移除，部署成功后删除部署位行。

## 9. 第一期实现说明

与上文不同或上文未明确的处理：

- 生效部署位（出站地址、单条订阅的快照来源）：启用、未待删除、`priority` 最小；订阅侧另要求已部署且有部署快照。生效部署位只在保存时变化，落地节点保存后若生效部署位的服务器或接入域名变化，引用它的代理节点部署位标为待部署。
- 节点部署状态汇总：全部待删除为待删除；全部已部署为已部署；其余为待部署。列表的部署状态筛选按同一规则。
- 删除部署位或节点：已部署过（状态为已部署/待删除或有 `node_deployment` 记录）的部署位标为待删除，否则直接删除。
- 订阅名称：部署位 `name` 优先；多条时为空则为“节点名-服务器名”；单条时用节点名。
- 部署版本按部署位记录，版本号在部署位内递增；查看、还原接口增加 `nodeServerId` 参数，节点只有一个部署记录时可省略。还原只还原节点配置，不改部署位和 DNS 绑定。
- 迁移的 `disabled`：未分组节点禁用状态保留在节点上，部署位启用；分组节点任一成员启用则逻辑节点启用，成员禁用转为对应部署位停用。分组部署位一律写入原节点名作为显示名。
- 迁移 SQL：`db/migration/V2026101701__merge_node_group_into_node_server.sql`（第 5.3 步 2），`V2026101702__drop_legacy_node_group_columns.sql`（第 5.3 步 5，稳定后执行）；只读检查为 `scripts/node_group_merge_precheck.sql`。`NodeServer` 实体不映射 `legacy_node_id`，该列只供迁移使用。
- 移除了只在空 `node_deployment` 表时运行的旧数据初始化（`initializeFromLegacyDeployedNodes`）。
