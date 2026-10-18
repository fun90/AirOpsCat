# 节点多服务器部署方案（逻辑节点 + 部署位）

## 1. 目标

用“逻辑节点 + 部署位”替换现有节点组（`node.node_group`），简化节点交互：

1. 同一个节点配置可部署到多台服务器，配置只维护一份。
2. 可指定哪些服务器（部署位）暴露到订阅。

本文是已上线的第一期：数据模型、数据迁移、部署、订阅、前端交互，节点组已下线。

后续的服务器地区与类型、域名绑定、部署位自动关联和流量自动切换见 [`server-region-domain-binding-plan.md`](server-region-domain-binding-plan.md)。该文档落地后，本文中的节点 DNS 记录绑定（`node.dns_record_id`）、部署位接入域名（`access_host_id`）、手动维护部署位和订阅 DNS 单条输出规则（4.3）会被替换。

已确认的决策：

| 事项 | 结论 |
|------|------|
| 模型 | 一次到位改为逻辑节点 + 部署位 |
| 端口 | 部署位端口固定与节点一致，不支持按部署位覆盖 |

## 2. 现状

| 能力 | 现有实现 | 本方案的处理 |
|------|----------|--------------|
| 节点组 | `node.node_group` 字符串；同组节点类型相同、不同服务器，保存时由 `NodeGroupService` 对齐协议/端口/入站/出站 | 删除 |
| 组内用户合并 | `DeploymentDataLoader.buildAssociatedNodeClientsMap` 按组合并账号 | 删除，一个逻辑节点的账号天然共用 |
| 组展开部署 | `NodeGroupService.expandWithRelatedGroups` | 改为按节点展开到部署位 |
| 服务器流量周期 | `Server.bandwidth`（GB）、`Server.bandwidthDate`、`TrafficPeriodUtils.resolveServerPeriodStart/End`、`ServerTrafficStats` | 见新方案 |
| 流量采集 | `traffic-stats-collect` 任务，默认 `0 */15 * * * ?` | 见新方案 |
| 流量告警 | `ServerTrafficThresholdNotifier`，阈值默认 `0.85`，支持 0-1 或 0-100 写法 | 见新方案 |
| DNS 记录 | `DomainDnsRecord`，Cloudflare `batchChangeRecords`；`DomainDnsScheduleTask` 每分钟按时段改内容并整域 push | 见新方案 |
| 账号级订阅定制 | `AccountNodeSubscriptionDomainBinding`（账号 + 节点 → 可选 DNS 记录 + 可选展示部署位） | 保留，优先级最高 |

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
| `exposed` | 1/0。未绑定 DNS 时是否出现在订阅 |
| `priority` | 越小越优先。决定订阅排序、默认生效部署位 |
| `enabled` | 1/0。0 表示不部署到该服务器（原 `node.disabled` 迁移到这里） |
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

- 请求体用 `placements[]`（`serverId`、`accessHostId`、`exposed`、`priority`、`enabled`）替换 `serverId`、`accessHostId`、`nodeGroup`。
- 节点配置字段变更：所有未待删除的部署位标为待部署。
- 部署位增加：新部署位为待部署。部署位删除：标为待删除，部署时从该服务器移除入站后再删行。
- 删除节点：所有部署位标为待删除，最后一个部署位删除后再删节点行。
- 校验：至少一个启用的部署位；同节点不能重复选同一服务器；每个启用部署位所在服务器的端口不冲突；绑定的 DNS 记录类型只能是 `A` 或 `CNAME`，未开启 Cloudflare 代理，未开启时段计划（`scheduleEnabled`），且未被其他节点绑定。

### 4.2 部署

- 部署单元从节点改为部署位。`deployNodes(nodeIds)` 展开为这些节点的待部署部署位，再按 `server_id` 分组调用现有 `processNodesByServer` 流程。
- `DeploymentDataLoader` 按服务器加载“该服务器上的部署位 → 逻辑节点”。快照的 `serverIp` 取部署位所在服务器。原先全局按 `nodeId` 建的快照 map，改为在每个 `DeploymentServerContext` 内按 `nodeId` 建（同一服务器上 `nodeId` 唯一）。
- 账号列表直接取逻辑节点的 `buildNodeClientsMap`，删除 `buildAssociatedNodeClientsMap`、`mergeClients` 的组合并逻辑。
- **出站 `out_id` 与路由规则 `outbound_node_id`**：仍指向逻辑落地节点。落地节点有多个部署位时，出站地址取“生效部署位”：取启用、已部署、`priority` 最小的部署位。落地节点生效部署位变化时，引用它的代理节点部署位标为待部署。
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

所有订阅条目均使用节点名称。

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

## 6. 验证

| 检查 |
|------|
| `./mvnw test`；为订阅条目生成规则（4.3 三种情况）补单元测试；在迁移库副本上执行 5.2 检查和 5.3 迁移，核对第 4 步校验；开发模式下验证节点增删改、部署位增删、单节点部署、服务器部署、订阅输出 |

## 7. 待实现时确认

1. 前端是否已有可复用的 DNS 记录选择组件（账号订阅域名绑定处）。已确认：复用 `/api/admin/domain-dns-records` + `createRemoteSearchConfig` 的 Tom Select。
2. `CoreDeploymentExecutor` 删除入站的路径，是否能按部署位只删除某台服务器上的入站。已确认：部署按服务器重建完整配置，待删除部署位不进入快照即从该服务器移除，部署成功后删除部署位行。

## 8. 第一期实现说明

与上文不同或上文未明确的处理：

- 生效部署位（出站地址、单条订阅的快照来源）：启用、未待删除、`priority` 最小；订阅侧另要求已部署且有部署快照。生效部署位只在保存时变化，落地节点保存后若生效部署位的服务器或接入域名变化，引用它的代理节点部署位标为待部署。
- 节点部署状态汇总：全部待删除为待删除；全部已部署为已部署；其余为待部署。列表的部署状态筛选按同一规则。
- 删除部署位或节点：已部署过（状态为已部署/待删除或有 `node_deployment` 记录）的部署位标为待删除，否则直接删除。
- 订阅名称始终使用节点名称。
- 部署版本按部署位记录，版本号在部署位内递增；查看、还原接口增加 `nodeServerId` 参数，节点只有一个部署记录时可省略。还原只还原节点配置，不改部署位和 DNS 绑定。
- 迁移的 `disabled`：未分组节点禁用状态保留在节点上，部署位启用；分组节点任一成员启用则逻辑节点启用，成员禁用转为对应部署位停用。
- 迁移 SQL：`db/migration/V2026101701__merge_node_group_into_node_server.sql`（第 5.3 步 2），`V2026101702__drop_legacy_node_group_columns.sql`（第 5.3 步 5，稳定后执行）；只读检查为 `scripts/node_group_merge_precheck.sql`。`NodeServer` 实体不映射 `legacy_node_id`，该列只供迁移使用。
- 移除了只在空 `node_deployment` 表时运行的旧数据初始化（`initializeFromLegacyDeployedNodes`）。
