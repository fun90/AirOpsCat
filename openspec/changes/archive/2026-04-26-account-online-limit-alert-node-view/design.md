## Context

现有在线统计链路已经由 `AccountOnlineRefreshTask` 定时调用 `SingBoxOnlineConnectionService.refreshAllServers()`，通过 sing-box Clash API `/connections` 获取当前连接，并由 `AccountOnlineIpService.refreshFromConnections(serverIp, connections)` 写入 `account_online_ip`。当前表的唯一键是 `account_no + client_ip + node_ip`，其中 `node_ip` 实际写入服务器 IP，因此可以区分不同服务器，但无法区分同一服务器上的不同 AirOpsCat 节点，也不能表达同一账户在同一 IP 下建立多条连接的并发占用。

账户实体已有 `maxOnlineIps` 字段，前端也展示为“最大在线 IP 数”。该定义已经过时，本次设计将账户限制统一改为“最大连接数”，并在代码、接口和页面层面去除旧语义。节点实体的运行入站 tag 由 `SingBoxConfigBuilder` 固定写成 `node_<id>`，因此可以从连接 metadata 的入站标识映射回逻辑节点。

## Goals / Non-Goals

**Goals:**

- 清理现有最大在线 IP 数代码，并将账户限制字段和展示口径统一为最大连接数。
- 在线刷新后检查账户当前有效在线连接数，超过最大连接数时发送 Bark 告警。
- 前端支持展示在线连接数，并可查看在线连接详情。
- 在线记录保留服务器维度，同时增加逻辑节点维度信息。
- 账户在线连接详情展示节点信息，节点页面可以查看当前在线账户。
- 尽量复用现有定时任务、Bark 通知、系统配置、Tabler/petite-vue 页面模式。

**Non-Goals:**

- 不在本次实现中强制断开超限连接，只做告警通知。
- 不改变订阅生成、节点授权、流量统计链路。
- 不引入新的通知渠道，继续复用 Bark。
- 不在本期迁移现有服务器流量、服务器负载等其他告警到新的告警状态模型。
- 不删除历史 `node_ip` 字段，避免影响现有服务器在线账户统计；但该字段不再代表限制计数口径。

## Decisions

### 1. 在线记录新增逻辑节点字段，保留 `node_ip` 作为服务器 IP

新增字段建议：

- `node_id`：可为空，映射到 AirOpsCat `Node.id`。
- `node_tag`：可为空，保存 sing-box 入站 tag，例如 `node_123`。
- `connection_id`：可为空，保存 sing-box Clash API 连接 ID，用于区分同一账户、客户端 IP、服务器、节点下的不同连接。
- DTO 增加 `nodeId`、`nodeName`、`nodeTag`、`serverIp` 或继续用 `nodeIp` 承载服务器 IP。

`node_ip` 继续保存服务器 IP，用于兼容现有服务器在线账户统计和 `/api/admin/servers/{id}/online-accounts`。节点视角查询使用 `node_id`，当连接 metadata 缺少入站信息或无法映射节点时，记录仍可保存为服务器维度记录，但不会出现在具体节点详情中。

备选方案是把 `node_ip` 改为逻辑节点标识，但这会破坏当前服务器统计语义，也会让已有接口含义发生变化，因此不采用。

### 2. 采集时从连接 metadata 映射节点

扩展 `SingBoxConnectionMetadata`，兼容读取 sing-box Clash API 可能返回的入站字段，例如 `inbound`、`inboundName` 或 `inboundTag`。采集单台服务器时，提前加载该服务器下已部署、未禁用的代理节点，按 `node.getTag()` 建立 `node_<id> -> Node` 映射。

写入在线记录时使用：

- `id` -> `connectionId`
- `authUser` -> `accountNo`
- `sourceIP` -> `clientIp`
- 服务器 IP -> `nodeIp`
- 入站 tag -> `nodeTag`
- tag 映射结果 -> `nodeId`

同一轮内按 `accountNo + connectionId + serverIp` 优先去重；当连接 ID 缺失时，退化为 `accountNo + clientIp + serverIp + nodeTag + destinationIP + destinationPort` 去重。数据库唯一约束也调整为连接口径，避免同一账户同一客户端 IP 在同一节点上建立多条连接时互相覆盖。

### 3. 超限判断按“在线连接数”计算

账户限制口径改为“最大连接数”，超限判断按当前有效窗口内账户在线连接记录数计算。每条可识别连接对应 1 个连接占用；同一客户端 IP 同时连接多个节点或同一节点下建立多条连接时，均按实际在线连接记录数累计。

告警内容中同时给出：

- 账户编号、备注、所属用户。
- 当前在线连接数、限制数。
- 去重客户端 IP 数，作为辅助排查信息。
- 主要 IP、节点和连接分布摘要。

旧 `maxOnlineIps` 字段和相关代码应在本期清理。实现时新增 `maxConnections` / `max_connections`，并将实体、DTO、请求对象、表单、详情展示、列表字段统一迁移到最大连接数。数据库迁移可以将历史 `max_online_ips` 值复制为 `max_connections` 初始值，随后代码不再读取、写入或展示 `maxOnlineIps`；旧列是否物理删除按项目数据库迁移策略决定，但应用层不再依赖。

### 4. 新增通用告警状态模型，本期只接入账户连接数告警

为了避免为连接数单独做一套无法复用的状态表，本期新增通用告警状态实体，例如 `AlertState`，但只由账户连接数超限告警写入和读取。现有服务器流量、服务器负载等告警暂不改造，仍保持当前逻辑。

建议字段：

- `alert_type`：告警类型，本期使用 `account-connection-limit`。
- `resource_type`：资源类型，本期使用 `account`。
- `resource_id`：资源 ID，本期保存账户 ID。
- `resource_key`：资源业务键，本期保存账户编号。
- `fingerprint`：告警指纹，用于同一资源同一类告警去重。
- `status`：`ACTIVE` 或 `RECOVERED`。
- `severity`：告警级别，默认 `WARNING`。
- `first_triggered_time`、`last_triggered_time`、`last_notified_time`、`recovered_time`。
- `trigger_count`、`current_value`、`threshold_value`、`summary`。
- `create_time`、`update_time`。

唯一约束建议使用 `alert_type + resource_type + resource_id + fingerprint`，让后续其他告警可以复用同一模型。

连接数告警流程：

- 账户当前连接数超过最大连接数时，按通用告警状态查找或创建 ACTIVE 状态。
- 首次 ACTIVE 立即通知。
- 已 ACTIVE 时按 `last_notified_time` 和配置的最小通知间隔决定是否重复通知。
- 账户恢复到限制以内时，将状态更新为 RECOVERED，并记录恢复时间；本期可默认不发送恢复通知。

备选方案是本期新增账户连接数专用状态表，但会很快和现有流量、负载等告警重复，因此不采用。

### 5. 告警检查挂在在线刷新任务后

在 `SingBoxOnlineConnectionService.refreshAllServers()` 成功完成各服务器刷新后，调用新的 `AccountOnlineLimitAlertService.checkAndNotify()`。这样检查基于最新采集结果，不需要新增独立定时任务，也能复用 `airopscat.account.online.refresh-minutes` 的执行频率。

单台服务器采集失败时仍继续处理其他服务器；超限检查应基于当前有效窗口内的全量记录执行，并在通知内容中避免暗示采集百分百成功。

### 6. 节点与账户页面查看在线连接数

新增节点维度接口，例如：

- `GET /api/admin/nodes/{id}/online-accounts`

接口从本地 `account_online_ip` 当前有效窗口读取 `node_id = :id` 的记录，返回 `AccountOnlineIpDto` 列表。前端节点表格增加在线连接数和查看入口，或在节点详情弹窗中加入在线连接区域。

账户页面也应展示当前在线连接数，并提供查看在线连接详情入口。详情中展示连接 ID、客户端 IP、服务器 IP、节点、最后在线时间和在线时长。连接 ID 过长时前端可使用短文本加 tooltip 展示，避免表格撑开。

实时读取 Clash API 会增加 SSH 延迟和失败面，也会和定时统计口径不一致，因此节点查看优先使用本地刷新结果。

## Risks / Trade-offs

- [Risk] sing-box 不同版本 metadata 入站字段名可能不同，导致节点映射为空。→ Mitigation：DTO 兼容多个字段名；无法映射时保留服务器维度记录，并在日志中以 debug 记录未映射 tag。
- [Risk] 调整唯一约束可能无法依赖 Hibernate `update` 自动完成。→ Mitigation：实现时提供明确的数据库迁移 SQL 或启动兼容逻辑，保留旧数据可查询；部署前说明需检查唯一索引。
- [Risk] 在线刷新失败会导致超限判断基于部分数据。→ Mitigation：超限检查仍执行，但告警文案只描述“当前统计窗口内已采集到的在线连接”；采集失败继续记录日志。
- [Risk] 清理 `maxOnlineIps` 可能遗漏前端或 DTO 引用。→ Mitigation：实现时全局搜索 `maxOnlineIps`、`最大在线 IP 数`、`最大在线IP数`，确保应用层无残留引用。
- [Risk] 某些连接缺少稳定连接 ID，可能影响精确连接数。→ Mitigation：优先使用 Clash API 连接 ID，缺失时使用客户端、服务器、节点、目标地址端口组合退化去重，并在设计中接受该场景下的近似统计。
- [Risk] 通用告警状态模型本期只接入一种告警，字段可能多于当前需求。→ Mitigation：字段控制在告警类型、资源、状态、阈值、通知时间等通用最小集合，不迁移其他告警，降低范围。
- [Risk] Bark 配置异常时告警无法送达。→ Mitigation：复用 BarkService 返回值并记录失败日志，不阻塞在线刷新任务。

## Migration Plan

1. 数据库账户表新增 `max_connections`，将历史 `max_online_ips` 值复制为初始最大连接数；清理应用层 `maxOnlineIps` 代码和页面文案。
2. 数据库为 `account_online_ip` 增加 `connection_id`、`node_id`、`node_tag` 字段，并增加连接口径唯一约束。
3. 如果旧唯一约束 `account_no + client_ip + node_ip` 存在，需要在迁移脚本中删除或替换；历史记录的 `connection_id`、`node_id` 和 `node_tag` 允许为空。
4. 新增通用告警状态表，初始为空；本期仅账户连接数超限告警写入该表。
5. 部署新版本后，下一轮在线刷新会逐步按连接口径写入新记录，并在超限时创建或更新告警状态。
6. 回滚时保留新增字段和告警状态表不影响旧代码读取；旧代码仍按 `node_ip` 维度工作，但不会理解最大连接数字段。

## Open Questions

- 节点页面入口放在节点列表行操作中，还是仅放在节点详情弹窗中？建议先复用服务器在线账户弹窗模式，在节点列表增加在线数量和查看按钮。
- 后续是否迁移服务器流量、服务器负载等告警到通用告警状态模型？建议另起 change，避免本期范围膨胀。
