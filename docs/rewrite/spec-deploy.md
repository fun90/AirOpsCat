# Go 重写规格：sing-box 配置生成、部署与服务器管理

> 来源：AirOpsCat `rewrite/go` 分支现有 Java 代码（只读调研，2026-10-18）与 sing-box fork（`fun90/sing-box`，`stable` 分支，HEAD `92fe3963`）。
> 约定：路径中 `J/` = `AirOpsCat/src/main/java/com/fun90/airopscat/`，`R/` = `AirOpsCat/src/main/resources/`，`SB/` = `sing-box/`，`P/` = `profile/airopscat/shell/`。
> 文中所有密钥、密码、UUID、Token 均为占位符。标“未确认”的结论未在代码中找到直接证据。
> 本文描述**现状行为**；“Go 实现建议”段落是建议，需与 `scope.md` 的决策对齐。

---

## 1. 领域模型与生命周期

### 1.1 三个实体

| 实体 | 表 | 关键字段 | 说明 |
|------|----|----------|------|
| 节点 `Node` | `node` | `port`、`protocol`、`core_type`、`type`(0 代理/1 落地)、`inbound`(JSON)、`out_id`、`rule`(JSON)、`level`、`disabled`、`region`、标签 `node_tag` | 逻辑节点。`tag` 不落库，恒为 `"node_" + id`（`J/model/entity/Node.java:101-103`） |
| 部署位 `NodeServer` | `node_server` | `node_id`、`server_id`（唯一约束）、`access_host_id`、`exposed`、`priority`、`deployed`(0 待部署/1 已部署/2 待删除)、`cheap_start/end` | 节点在某台服务器上的实例（`J/model/entity/NodeServer.java:12-59`） |
| 服务器 `Server` | `server` | `ip`、`ssh_port`、`auth_type`、`username`、`auth`(加密)、`region`、`server_type`(0 代理/1 落地)、`disabled`、`external_server`、`expire_date`、`transit_config`、`core_config` | SSH 目标。`transit_config` 被装入 `ServerSnapshot` 但配置生成**未使用**（`J/model/dto/deployment/ServerSnapshot.java:5`，全仓库无读取） |

- 协议与节点类型绑定（`J/model/enums/ProtocolType.java`）：代理节点(type=0)只能用 `vless`、`vless-reality`、`hysteria2`、`shadowtls`；落地节点(type=1)只能用 `shadowsocks`、`socks`。
- 一个节点同一服务器最多一个部署位；端口冲突按“同服务器上其他节点的同端口”判定，**包含待删除部署位**（`J/repository/NodeServerRepository.java:72-85`）。
- “生效部署位”：非待删除中 `priority` 最小、再按 `id` 最小（`NodeServerRepository.java:17-19, 102-109`）。用于落地节点作出站时取其服务器 IP。
- 节点部署状态汇总：全部待删除→待删除；全部已部署→已部署；否则待部署（`NodeServerRepository.java:118-129`）。

### 1.2 部署位自动同步（`NodePlacementSyncService`）

> **Go 版已调整（取代下述同步）**：只在新建节点时按下述匹配规则创建初始部署位（端口冲突报错）；之后地区变化、服务器新建/修改都不再增删部署位。管理员在节点编辑页暂存部署位增删，点保存后与节点修改在同一事务中提交（任一校验失败全部回滚）；手动添加部署位，不限地区，但仍遵守类型规则（代理节点只能落在非外部代理服务器），并校验端口冲突；同服务器的待删除部署位重新添加时恢复为待部署。手动删除部署位走 `removeOrMarkDeleted`；节点最后一个有效部署位禁止删除。删除时其访问域名与订阅定制须转移到同节点另一部署过的部署位（当前已部署，或待部署但有部署记录）；目标已有访问域名时保留目标的：A 记录改绑目标服务器、解析值改为目标 IP、清除切换前原值、置待更新，推送 DNS 后生效；域名被其他节点使用、存在未完成的 DNS 自动操作、时段计划或待删除记录时拒绝。节点改类型、服务器改为落地/外部时，若已有部署位违反类型规则则拒绝保存。跨地区部署位参与 DNS 自动切换。Hysteria2 默认入站主机名对已有节点取生效部署位，不看地区。

**匹配规则** `matches(node, server)`（`J/service/NodePlacementSyncService.java:50-64`），不看服务器是否禁用：

1. `node.region` 非空白且 `server.region` 非 null，二者 `trim()` 后**区分大小写**相等；否则不匹配。
2. `proxyNode = node.type != 1`。代理节点遇到 `server.external == 1` → 不匹配。
3. `proxyServer = server.serverType != 1`。结果 = `!proxyNode || proxyServer`：
   - 代理节点：只能落在非外部的代理服务器；
   - 落地节点：可落在任何服务器（落地或代理，含外部服务器）。

**新部署位候选** `findCandidateServers`：`matches` 且 `server.disabled != 1`（`:67-72`）。

**`syncNode(node, strict)`**（`:80-128`，`synchronized` + `@Transactional`）：

1. `node.region` 为空白 → 直接返回，不动任何部署位（保护未回填地区的存量节点）。
2. 遍历已有部署位：
   - 服务器存在且匹配：若为待删除(2) → 恢复为待部署(0)；其余不动。
   - 服务器不存在或不再匹配，且当前不是待删除 → `removeOrMarkDeleted`：
     - “曾部署过”（`deployed` 为 1 或 2，或存在 `node_deployment` 记录，`:138-142`）→ 标记待删除(2)；
     - 否则物理删除：先删 `account_node_subscription_placement` 中引用，再删部署位，最后释放不再被引用的访问域名记录（`:154-163`）。
3. 对候选服务器中尚无部署位者（按 `server_id` 判断）：
   - 端口冲突：`strict=true` 抛 `IllegalArgumentException`（节点保存/更新时），`strict=false` 记日志跳过（服务器变更触发的全量同步）。
   - 新建部署位：`exposed = 落地节点 ? 0 : 1`，`priority` = 已有最大 priority（无则 0）递增，`deployed = 0`，不设域名与便宜时段（`:118-126`）。
4. 同步**只增删部署位**，不改已有部署位的 priority、exposed、域名。

**触发点**：

| 触发 | 调用 | 位置 |
|------|------|------|
| 节点新建 | `syncNode(node, true)` 后应用部署位请求 | `J/service/NodeService.java:372-381` |
| 节点更新 | 校验已有部署位端口 → `syncNode(existing, true)` | `NodeService.java:387-434` |
| 服务器新建 / 更新 / 启用 | `syncAll()` = 对所有节点 `syncNode(node,false)`；失败只记日志，不影响服务器保存 | `J/controller/ServerController.java:68-75, 205-224, 315-327`；`NodePlacementSyncService.java:132-134` |
| 服务器禁用 | **不触发**同步（已有部署位保留，只是不再新生成） | `ServerController.java:329-340` |

注意：`syncNode` 的 `synchronized` 是 JVM 内锁，Go 版需用进程内互斥或在单事务内完成。

### 1.3 部署位状态机

```
           syncNode新建/实质变更/取消删除/重新匹配
                ┌───────────────────────────┐
                ▼                           │
  (新建) ──► 0 待部署 ──部署成功──► 1 已部署 ──实质变更──┘
                │                     │
         从未部署:物理删除      不再匹配/删除节点
                                      ▼
                               2 待删除 ──部署成功──► 物理删除部署位(及其部署记录)
                                                    └► 若为节点最后一个部署位：物理删除节点
```

- 标记待部署 `markPendingDeploy` 跳过待删除部署位（`NodeService.java:820-825`）。
- “实质变更”签名：`port|type|protocol|inbound(规范化JSON)|rule(规范化JSON)|outId|level(null=0)|disabled(null=0)[|tags]` 的 MD5（`NodeService.java:602-627`）。更新节点时含标签；变更则该节点全部部署位置待部署（`:419`）。
- 落地节点配置（不含标签）或其生效部署位所在服务器变化 → 所有 `out_id` 指向它的代理节点置待部署（`NodeService.java:500-516`）。
- 启用/禁用节点：本节点 + 引用它的代理节点置待部署（`:805-816`）。
- 批量改标签：变化的节点置待部署（`:520-578`）。
- 删除节点：各部署位 `removeOrMarkDeleted`；无剩余部署位则物理删除节点（`:700-714`）。取消删除：待删除→待部署，需再次校验端口（`:721-738`）。
- 部署成功后对待删除部署位调用 `physicallyDeletePlacement`：删 `node_deployment`、`node_deployment_history`、订阅定制引用、部署位，节点无部署位时删节点（`:771-781`）。
- 删除服务器：存在任一“曾部署过”的部署位则拒绝；否则删掉其从未部署的部署位等（`J/service/ServerService.java:376-393`）。

---

## 2. 单台服务器 sing-box 配置生成算法

入口：`SingBoxConfigBuilder.build(ctx, placements, overrides)`（`J/singbox/SingBoxConfigBuilder.java:46-84`）。数据装载：`DeploymentDataLoader`（`J/service/deployment/DeploymentDataLoader.java`）。

### 2.1 输入数据装载

对目标服务器 S：

1. **部署位**：S 上全部 `node_server`（不论状态，`DeploymentDataLoader.java:51-52, 77`），按 `priority, id` 排序（`J/repository/NodeServerRepository.java:40-52`）；入站、节点出站与节点路由规则都按此顺序追加。构建快照时剔除待删除(2)部署位（`:82-84`）——待删除节点的入站因此从配置中消失。
2. **路由规则**：`route_rule` 中 `enabled=1`、`lower(trim(core_type))='sing-box'`、且绑定了 S 的规则（`J/repository/RouteRuleRepository.java:12-20`）。查询**无 ORDER BY**，规则顺序未确认（依赖数据库返回顺序）。
3. **节点快照** `NodeDeploymentSnapshot`（`DeploymentDataLoader.java:104-191`）：
   - 本地节点：S 上非待删除部署位对应的节点；`serverIp = S.ip`；`disabled` 取节点 `disabled`。
   - 若节点有 `out_id`：取出站节点及其**生效部署位**的服务器 IP；出站节点存在但无生效部署位 → 抛 `IllegalArgumentException("出站节点没有可用的部署服务器")`，整台服务器部署失败（`:165-168`）。
   - 路由规则引用的出站节点（未在本地出现时）：用其生效部署位服务器 IP 建快照，`clients` 为空；无生效部署位则静默跳过（`:147-156`）。
4. **账户（clients）**（`:193-240`）：仅 `core_type == "sing-box"` 的节点。节点标签 ∩ 账户标签（共享 `tag`）关联的账户，过滤条件 `a.disabled = 0 and (a.toDate is null or a.toDate > now)`（`J/repository/TagRepository.java:62-70`）。每个账户映射为：

   | NodeClient 字段 | 来源 |
   |-----------------|------|
   | `id` | `account.uuid`（保存时规范化为标准 UUID 且唯一，`J/service/AccountService.java` `normalizeAndValidateUuid`） |
   | `email` | `account.accountNo`（即 sing-box 用户 `name`，也是统计 / 限速 / 跨节点配额的键） |
   | `flow` | 恒为 `"xtls-rprx-vision"` |
   | `downloadMbps` / `uploadMbps` / `maxIps` | 账户同名字段 |

5. **限速覆盖**（仅 `airopscat.ratelimit.enabled=true` 时，`J/service/deployment/CoreDeploymentExecutor.java:112-117`）：`RateLimitService.buildOverrides`（`J/service/ratelimit/RateLimitService.java:180-211`）对本服务器快照中出现的每个 `accountNo`，取当前周期流量统计，经 `AccountTrafficLimitService.resolveEffectiveMbps`：
   - 本期已用(上+下) ≥ 有效配额（`stats.bandwidthQuota` 优先，否则 `account.bandwidth`，单位 GB，≤0 不限）→ 覆盖为系统配置的超额限速（默认下行/上行各 1 Mbps，最小 1）；
   - 否则覆盖为账户自身 `downloadMbps/uploadMbps`；两者均为 null 时不放入覆盖。
   - 覆盖存在时**整体替换**账户自身的两个值（`SingBoxConfigBuilder.java:177-185`）。
   - `previewServerConfigs`（`ServerController` 的 `/config-preview`）**不应用覆盖**（`J/service/deployment/NodeDeploymentService.java:171-186`），预览与实际下发可能不同。

### 2.2 生成步骤

```
enabledNodes = 本地节点快照中 disabled == 0 者（按部署位顺序）       // :69-71
for node in enabledNodes:                                              // :77-79, 86-118
    if node.inbound == null: 跳过
    inbound = parse(node.inbound) 后改写（2.3）
    inbounds += inbound
    statsUsers += (protocol ∈ {shadowsocks, socks}) ? inbound.users[*].name|username
                                                     : node.clients[*].email（非空）
    if node.outId != null: 追加出站（2.4，按 tag 去重）
    if node.outTag != null: routeRules += {"inbound":[node.tag], "outbound": node.outTag}
applyManagedRouteRules（2.5）
renderConfig(base 模板)（2.6）
```

注意：禁用节点不生成入站，但其部署位在部署成功后仍被置为已部署（见 3.4）。

### 2.3 入站字段填充（`buildInbound`，`SingBoxConfigBuilder.java:120-134`）

对数据库中的 `node.inbound` JSON（自由格式，管理员可编辑）做以下改写，其余字段**原样保留**：

1. 删除 `tls.reality.public_key`（公钥只供订阅使用，sing-box 服务端不接受该字段）。
2. 覆盖 `tag = "node_<id>"`、`listen = "::"`、`listen_port = node.port`。
3. `users` 合并（`mergeUsers`，`:194-205`）：先放入 inbound 原有 users，再用托管 users 按键覆盖/追加，键优先级见下表；托管用户与原有用户键相同时**托管用户整体替换**。

| 协议值 | 托管用户结构 | 合并键 | 说明 |
|--------|--------------|--------|------|
| `vless`、`vless-reality` | `{"uuid": account.uuid, "name": accountNo, "flow": "xtls-rprx-vision", [download_mbps], [upload_mbps], [max_ips]}`（`:150-163`） | `uuid` | 两种 vless 都带 `flow` |
| `hysteria2` | `{"name": accountNo, "password": account.uuid, [download_mbps], [upload_mbps], [max_ips]}`（`:165-175`） | `name`，再 `password` | 密码即账户 UUID |
| `shadowsocks`、`socks`、`shadowtls` | 不注入，保持 inbound 原样 | — | ss/socks 的统计用户取 inbound 内用户名；shadowtls 的统计用户却取托管账户（`:102-110`），与实际入站用户不一致 |

限速 / IP 字段（`putRateLimitFields`，`:177-192`）仅在值 `> 0` 时写入：`download_mbps`、`upload_mbps`（取覆盖或账户值）、`max_ips`（账户值，不受覆盖影响）。这三个字段是 **fork 专有字段**，见 2.8。

**各模板的其余字段来源**（节点创建时由 `SingBoxDefaultInboundFactory` 生成默认 inbound，管理员可再编辑后存入 `node.inbound`；`J/singbox/SingBoxDefaultInboundFactory.java:46-126`）：

| 协议 | 模板 | 生成值 |
|------|------|--------|
| vless | `R/config/core/sing-box-inbound-vless.json` | TLS 证书固定 `/usr/local/etc/certs/fullchain.cer` 与 `private.key`，TLS 1.2–1.3，ALPN h2/http1.1；模板数据里的 `uuid`、`serverName` 在模板中未被引用 |
| vless-reality | `sing-box-inbound-vless-reality.json` | `server_name`/`handshake.server` 从 `www.apple.com`/`www.icloud.com`/`www.amazon.com` 随机取一；`private_key`/`public_key` 由**控制端本机**执行 `sing-box generate reality-keypair` 得到（`:128-160`），失败时回退到源码中硬编码的一对密钥（安全风险，Go 版必须删除）；`short_id` 为 16 位随机十六进制 |
| hysteria2 | `sing-box-inbound-hysteria2.json` | `up_mbps/down_mbps = 24`；TLS 证书路径同上，ALPN h3；`server_name` 与 masquerade `url` = 部署位访问域名（`access_host_id` 对应 DNS 记录全名）或服务器 host；`obfs.salamander.password` 20 位随机字母数字 |
| shadowtls | `sing-box-inbound-shadowtls.json` | v3，单用户 `default` + 随机密码，握手服务器随机取一，`strict_mode=false`；模板未设置 `detour`，实际是否可用未确认 |
| shadowsocks | `sing-box-inbound-shadowsocks.json` | `aes-256-gcm`，单用户：12 位随机 `name` + 20 位随机密码 |
| socks | `sing-box-inbound-socks.json` | 单用户 `user_<12位随机>` + 20 位随机密码 |

随机数使用 `ThreadLocalRandom`（`J/util/NativeRandomUtils.java:18-37`），非密码学安全；Go 版应改用 `crypto/rand`，Reality 密钥对用 `crypto/ecdh` X25519 生成并 base64 RawURL 编码，不再依赖控制端安装 sing-box。

### 2.4 出站（落地节点）

代理节点 `out_id` 指向落地节点时（`addOutboundIfAbsent` / `buildOutboundFromInbound`，`SingBoxConfigBuilder.java:217-334`）：

- 出站 `tag = 落地节点 tag`（`node_<落地id>`）；与 `default-direct`、`default-blocked` 或已存在 tag 重复时不重复添加。
- 由落地节点**数据库中的 inbound JSON**（不含托管用户）反推出站：

| 落地协议 | 出站字段 |
|----------|----------|
| shadowsocks | `type, server=落地生效服务器IP, server_port=落地端口, method, password=users[0].password（无 users 时取顶层 password）` |
| socks | `type, server, server_port, username/password=users[0]` |
| vless / vless-reality | `uuid/flow=users[0]`，复制 `tls`、`transport`（复制的是重新解析的落地 inbound，服务端 `tls` 字段如 `private_key`、`certificate_path` 会原样进入出站，属潜在问题） |
| hysteria2 | `password=users[0].password`，复制 `tls`、`up_mbps`、`down_mbps` |
| 其他 | 记警告，不生成 |

由于落地节点协议仅允许 ss/socks（1.1），vless/hysteria2 分支实际不可达。

### 2.5 路由规则进入配置（`applyManagedRouteRules`，`SingBoxConfigBuilder.java:365-534`）

对每条启用规则：

1. 跳过 `coreType` 非 sing-box 的规则；出站节点快照缺失或无 inbound/IP/端口 → 记警告跳过；出站节点 coreType 非 sing-box → 跳过。
2. 以出站节点 tag 追加出站（同 2.4，去重）。
3. 按规则类型生成 route rule（`J/model/enums/RouteRuleType.java`）：

| ruleType | sing-box 字段 | 生成方式 |
|----------|---------------|----------|
| `domain` / `ip` / `source_ip` | `domain` / `ip_cidr` / `source_ip_cidr` | 值规范为列表；`geosite:<name>` → 远程 rule_set `geosite-<name小写>`（`https://raw.githubusercontent.com/SagerNet/sing-geosite/refs/heads/rule-set/geosite-<name>.srs`），`geoip:<name>` → `geoip-<name>`（sing-geoip 同构 URL）；普通值合成一条规则（单值时不包列表），rule_set 引用合成另一条 `{"rule_set":[...],"outbound":tag}` |
| `port` / `network` / `inbound` | `port` / `network` / `inbound` | `{字段: 原值, "outbound": tag}` |
| `custom` | — | 规则值（JSON 对象）原样展开，再覆盖 `outbound` |

- 新增的 rule_set 按 tag 去重；`geoip-cn`、`geosite-cn` 已在基础模板中，跳过（`:497-508`）。远程 rule_set 统一 `"type":"remote","format":"binary","http_client":"default-http-client"`。
- **顺序**：基础 4 条规则 → 节点出站规则（`inbound`→落地）→ 托管路由规则。节点出站规则排在前面，故挂了落地的代理节点的全部流量先被节点规则命中，路由规则对其无效。未匹配的流量走第一个出站 `default-direct`。
- 路由规则的增删改、启停**不会**把任何部署位置为待部署，也不触发部署（`J/service/RouteRuleService.java:123-159`），需手工强制部署相关节点。

### 2.6 基础模板与统计配置

基础模板 `R/config/core/sing-box.json`（Qute 语法，非纯 JSON），由 `renderConfig` 填充（`SingBoxConfigBuilder.java:343-363`）：

| 位置 | 内容 |
|------|------|
| `log` | `level=warn`，`output=/var/lib/sing-box/box.log`，带时间戳 |
| `experimental.clash_api` | `external_controller=127.0.0.1:19191`，`secret` = 系统配置 `airopscat.sing-box.clash-api.secret`（为空则输出空串，即不鉴权） |
| `experimental.cache_file` | `enabled`，`path=/var/lib/sing-box/cache.db`，`cache_id=airopscat` |
| `experimental.v2ray_api` | `listen=127.0.0.1:101`，`stats.enabled=true`，`stats.users` = 去重后的 statsUsers（无用户时省略）；未设置 `inbounds/outbounds` 统计 |
| `dns` | 单个 `tls` 服务器 `8.8.8.8`（tag `google`），`strategy=ipv4_only` |
| `http_clients` | `[{"tag":"default-http-client"}]`（sing-box 1.14 起的上游字段，非 fork 专有，`SB/option/options.go:23`） |
| `outbounds` 固定 | `default-direct`(direct)、`default-blocked`(block)，其后追加 2.4/2.5 的出站 |
| `route` | `auto_detect_interface=true`，`default_http_client=default-http-client`；规则：`sniff` → `port 53 hijack-dns` → `resolve prefer_ipv4` → `ip_is_private reject` → 追加规则；`rule_set` 固定 `geoip-cn`、`geosite-cn` + 追加 |

配置只包含上述字段，未设置 `route.final`。

**统计 / 管理接口的使用方式**：

- v2ray_api gRPC：每次查询经 SSH 本地端口转发（本地端口由系统分配）连到远端 `127.0.0.1:101`，明文 gRPC，`QueryStats(patterns=["user>>>.*>>>traffic>>>.*"], reset=true)`，超时 `airopscat.sing-box.grpc.timeout-seconds`（默认 10），失败重试 `max-retries`（默认 1）（`J/singbox/SingBoxGrpcQueryClient.java:41-60`；`J/singbox/SingBoxTrafficStatsCollector.java:29-31`）。服务名 `v2ray.core.app.stats.command.StatsService`（`AirOpsCat/src/main/proto/traffic_stats.proto`）。v2ray_api 需要构建标签 `with_v2ray_api`，fork 的 deb 发布已加入（`SB/.github/workflows/release-deb-amd64.yml:69`）。
- Clash API：同样经 SSH 本地端口转发访问 `GET/DELETE /connections[/{id}]`，`Authorization: Bearer <secret>`（`J/singbox/SingBoxClashApiClient.java:42-94`）。远端地址/端口来自系统配置 `airopscat.sing-box.clash-api.host/port`（默认 `127.0.0.1:19191`），但模板写死 `127.0.0.1:19191`，修改配置项会导致不一致。

### 2.7 输出示例（占位符）

场景：服务器 S 上有代理节点 12（vless-reality，端口 10001，`out_id=7`）、代理节点 13（hysteria2，端口 10002）；落地节点 7（shadowsocks，生效部署位在另一台服务器 `<LANDING_IP>`:20001）；一条路由规则 `domain = ["geosite:openai","example.com"] → 节点 7`；账户 `A10001`（下行 50 Mbps，max_ips 3）。

```json
{
  "log": {"level": "warn", "output": "/var/lib/sing-box/box.log", "timestamp": true},
  "experimental": {
    "clash_api": {"external_controller": "127.0.0.1:19191", "secret": "<CLASH_API_SECRET>"},
    "cache_file": {"enabled": true, "path": "/var/lib/sing-box/cache.db", "cache_id": "airopscat"},
    "v2ray_api": {"listen": "127.0.0.1:101", "stats": {"enabled": true, "users": ["A10001"]}}
  },
  "dns": {"servers": [{"type": "tls", "tag": "google", "server": "8.8.8.8"}], "strategy": "ipv4_only"},
  "http_clients": [{"tag": "default-http-client"}],
  "inbounds": [
    {
      "type": "vless",
      "users": [
        {"uuid": "<ACCOUNT_UUID>", "name": "A10001", "flow": "xtls-rprx-vision",
         "download_mbps": 50, "max_ips": 3}
      ],
      "tls": {
        "enabled": true, "server_name": "www.example-sni.com",
        "reality": {
          "enabled": true,
          "handshake": {"server": "www.example-sni.com", "server_port": 443},
          "private_key": "<REALITY_PRIVATE_KEY>",
          "short_id": ["<SHORT_ID>"]
        }
      },
      "tag": "node_12", "listen": "::", "listen_port": 10001
    },
    {
      "type": "hysteria2",
      "users": [{"name": "A10001", "password": "<ACCOUNT_UUID>", "download_mbps": 50, "max_ips": 3}],
      "up_mbps": 24, "down_mbps": 24,
      "tls": {"server_name": "<ACCESS_HOST>", "enabled": true, "alpn": ["h3"],
              "certificate_path": "/usr/local/etc/certs/fullchain.cer",
              "key_path": "/usr/local/etc/certs/private.key"},
      "obfs": {"type": "salamander", "password": "<OBFS_PASSWORD>"},
      "masquerade": {"type": "proxy", "url": "https://<ACCESS_HOST>/", "rewrite_host": true},
      "tag": "node_13", "listen": "::", "listen_port": 10002
    }
  ],
  "outbounds": [
    {"type": "direct", "tag": "default-direct"},
    {"type": "block", "tag": "default-blocked"},
    {"type": "shadowsocks", "server": "<LANDING_IP>", "server_port": 20001,
     "method": "aes-256-gcm", "password": "<LANDING_SS_PASSWORD>", "tag": "node_7"}
  ],
  "route": {
    "auto_detect_interface": true,
    "default_http_client": "default-http-client",
    "rules": [
      {"action": "sniff"},
      {"port": 53, "action": "hijack-dns"},
      {"action": "resolve", "strategy": "prefer_ipv4"},
      {"ip_is_private": true, "action": "reject"},
      {"inbound": ["node_12"], "outbound": "node_7"},
      {"domain": "example.com", "outbound": "node_7"},
      {"rule_set": ["geosite-openai"], "outbound": "node_7"}
    ],
    "rule_set": [
      {"tag": "geoip-cn", "type": "remote", "format": "binary",
       "url": "https://raw.githubusercontent.com/SagerNet/sing-geoip/refs/heads/rule-set/geoip-cn.srs",
       "http_client": "default-http-client"},
      {"tag": "geosite-cn", "type": "remote", "format": "binary",
       "url": "https://raw.githubusercontent.com/SagerNet/sing-geosite/refs/heads/rule-set/geosite-cn.srs",
       "http_client": "default-http-client"},
      {"tag": "geosite-openai", "type": "remote", "format": "binary",
       "url": "https://raw.githubusercontent.com/SagerNet/sing-geosite/refs/heads/rule-set/geosite-openai.srs",
       "http_client": "default-http-client"}
    ]
  }
}
```

（实际输出为模板拼接结果，缩进与换行不规整；Java 侧键序来自 `LinkedHashMap` 与原 JSON。Go 版无需逐字节兼容，因为服务端配置不是对外契约。）

### 2.8 fork 专有字段与行为

fork 基于上游 `stable` 合并（`git describe` 为 `v1.13.5-452-g378f151c`，安装脚本下载的发行版为 `v1.14.0`，`P/07-sing-box.sh:19-21`）。与 AirOpsCat 相关的改动：

| 字段 / 行为 | 定义位置 | AirOpsCat 使用情况 |
|-------------|----------|-------------------|
| 用户级 `download_mbps`、`upload_mbps`（float64，SI：1 Mbps = 125000 B/s） | `SB/option/inbound_user.go:3-5`；限速器 `SB/common/ratelimit/limiter.go:15-25`；连接包装 `SB/route/conn.go`（`ratelimit.WrapConn/WrapPacketConn`） | 写入 vless、hysteria2 用户 |
| 用户级 `max_ips`（本节点同时活跃的不同源 IP 数） | `SB/option/inbound_user.go:6-8`；`SB/common/connlimit/limiter.go:29` | 写入 vless、hysteria2 用户（账户 `maxIps`） |
| 用户级 `max_connections` | `SB/option/inbound_user.go:9-11` | **未使用** |
| 上述字段通过内嵌 `InboundUserSpeedOptions` 加到各协议用户结构 | `SB/option/vless.go:15`、`hysteria2.go:111`、`shadowsocks.go:17`，以及 trojan、vmess、tuic、anytls、shadowtls、snell、hysteria | 仅 vless/hysteria2 下发；socks 用户（`auth.User`）不支持 |
| 跨节点账户配额表（黑名单），只读 `/run/airopscat/account-quota.json`（环境变量 `AIROPSCAT_ACCOUNT_QUOTA_FILE` 可覆盖），每 2s 重载，过期 fail-open | `SB/common/accountquota/guard.go:1-46`；注册 `SB/box.go:244`；入口闸门 `SB/route/connlimit.go:26` | 不出现在配置中；由 `06-guard-agent.sh` 安装的 agent 写入，键为用户 `name`（即 accountNo） |
| Clash API `/connections` 元数据增加 `authUser` | `SB/experimental/clashapi/connections.go:90` | 在线 IP 采集与 guard agent 按账户聚合依赖此字段 |

结论：**用户 `name` 必须等于 `accountNo`**，限速、统计（`user>>>{name}>>>traffic`）、跨节点配额三处都以它为键。

---

## 3. 部署流程

### 3.1 触发点

现有系统**没有**“保存即部署”：节点/标签/启停变化只把部署位置为待部署，由以下入口触发实际部署。所有部署入口都是**同步**执行（HTTP 请求或定时任务线程等待完成），无防抖。

| 入口 | 选取的部署位 | 位置 |
|------|--------------|------|
| `POST /api/admin/nodes/{id}/deploy` | 该节点状态为 0 或 2 的部署位；无则返回“无需重复部署” | `J/controller/NodeController.java:379-384`；`NodeDeploymentService.java:70-86` |
| `POST /api/admin/nodes/{id}/deployForcibly` | 该节点全部部署位 | `NodeController.java:386-395`；`NodeDeploymentService.java:88-100` |
| `POST /api/admin/nodes/deploy-batch`（body: nodeId 列表） | 这些节点中状态 0/2 的部署位 | `NodeController.java:397-402` |
| `POST /api/admin/nodes/{id}/deployment-versions/{v}/restore`，body `redeploy=true` | 还原后强制部署该节点 | `NodeController.java:472-482` |
| `PATCH /api/admin/accounts/{id}/deploy` | 与账户共享标签的所有节点，强制 | `J/controller/AccountController.java:357-362`；`NodeDeploymentService.java:60-68` |
| 定时任务“过期账号处理”（cron `airopscat.account.expiration.cron`，默认 `0 0 5 * * ?`） | 已过期但未禁用账户关联的节点，强制部署后再禁用这些账户 | `J/scheduler/AccountExpirationTask.java:36-85`；`J/service/SystemConfigService.java:359` |
| 限速同步 `RateLimitService.triggerAsyncSync`（见 3.6） | 每台“运行目标服务器”整机重推（不走部署记录） | `RateLimitService.java:284-345` |

“选取的部署位”只决定**涉及哪些服务器**；每台服务器总是用其**全部**部署位重建完整配置（`DeploymentDataLoader.java:51-52`）。

账户新增 / 修改 / 删除只调用 `rateLimitService.triggerAsyncSync()`（`J/service/AccountService.java:377, 412, 449`），而该同步在限速开关关闭时直接返回（`RateLimitService.java:150-153`）。即**限速关闭时，账户变更不会自动下发到服务器**，需手动执行“账户部署”。

### 3.2 编排（`NodeDeploymentService.processPlacementsByServer`，`:102-147`）

1. `dataLoader.load(placements)` 按涉及的服务器构建上下文；服务器不存在则整个调用失败。
2. 丢弃没有部署位的服务器；`server.disabled == 1` 的服务器跳过，并为其每个部署位返回失败结果“服务器 X 已禁用，已跳过部署”。
3. 服务器按 `airopscat.deployment.max-parallel-servers`（默认 4，最小 1）分批；同批服务器在 `deploymentTaskExecutor` 线程池并行（核心 2、最大 8、队列 32，`J/config/BlockingTaskExecutorConfig.java:32-42, 82-86`），批与批串行（`future.join()`）。
4. 每台服务器结果交给 `persist` 写库（3.4）。
5. 整个方法在 `@Transactional` 内，SSH 操作期间持有数据库事务。Go 版应把“装载 → 生成 → 推送”放在事务外，只在写结果时开短事务。

### 3.3 单台服务器执行（`CoreDeploymentExecutor`，`:71-157`）

```
if server.external == 1 or 配置 airopscat.deployment.skip-remote-config == true:   // :159-169
    只生成配置，不连接服务器，视为成功
else:
    conn = SSH(server)                     // 连接失败 → 该服务器全部部署位失败
    config = build(ctx, 全部部署位, overrides?)
    CONFIG(config)   → 失败抛错 "配置上传失败: ..."
    RESTART          → 失败抛错 "配置上传成功，重启失败: ..."
```

`skip-remote-config` 在 dev profile 为 `true`（`R/application-dev.properties:12`）。

**CONFIG 操作**（`J/singbox/SingBoxCoreManager.java:52-106`）：

1. `mkdir -p /etc/sing-box`
2. `cp /etc/sing-box/config.json /etc/sing-box/config.json.backup.<epochMillis>`（首次部署时 cp 失败，仅告警）
3. SFTP 写入 `/etc/sing-box/config.json`（直接覆盖，非原子）
4. `/usr/bin/sing-box check -c /etc/sing-box/config.json`
5. 校验失败：若备份成功则 `mv 备份 → config.json` 还原，返回失败；备份失败时**无效配置留在磁盘**
6. 退出码 0 视为成功（`J/model/dto/CommandResult.java:13-15`）

**RESTART**：`systemctl restart sing-box`（`SingBoxCoreManager.java:108-139`）。其他可用操作：`start/stop/reload/status`（`status` 带 `--no-pager`）；install/uninstall/update 抛 `UnsupportedOperationException`。`CoreManagementService` 在同一连接中顺序执行多个操作，前一个失败则后续标记“操作未执行”（`J/service/core/CoreManagementService.java:46-72`）。

注意：`sing-box check` 只检查单文件，而 systemd 以 `-C /etc/sing-box` 加载目录下所有 `*.json`（`SB/release/config/sing-box.service:11`；`SB/cmd/sing-box/cmd_run.go:81-87`）。备份文件名后缀不是 `.json`，不会被加载；Go 版命名备份时也须避开 `.json` 后缀。

### 3.4 结果落库（`CoreDeploymentExecutor.persist`，`:181-209`）

- 失败：该服务器全部部署位返回 `success=false` 与消息，**状态不变**（仍为待部署/待删除）。
- 成功：遍历该服务器全部部署位：
  - 待删除 → `physicallyDeletePlacement`（1.3）；
  - 其他（含禁用节点、外部服务器）→ `deployed = 1`；
  - 随后 `NodeDeploymentVersionService.recordSuccessfulDeployments`。
- 返回 `DeploymentResult{nodeId, serverId, success, message}`；单节点接口会汇总为一条（`NodeController.java:496-526`）。

**`node_deployment` 记录**（`J/model/entity/NodeDeployment.java`；`J/service/deployment/NodeDeploymentVersionService.java:58-75, 172-198, 249-266`）每个部署位一行（`node_server_id` 唯一）：

| 字段 | 内容 |
|------|------|
| `node_id`、`node_server_id`、`server_id` | 关联 |
| `version` | 从 1 开始；当节点“实质变更”（1.3 的签名，含标签）或 `serverId` 变化时 +1，并把旧行归档到 `node_deployment_history` |
| `snapshot_json` | 节点快照：nodeId、nodeServerId、serverId、accessHostId、port、protocol、coreType、type、inbound、outId、rule、level、disabled、name、remark、tagIds |
| `snapshot_hash` | `snapshot_json` 的 MD5 十六进制 |
| `core_type`、`protocol`、`type`、`port`、`disabled`、`deployed_at` | 冗余字段 |

要点：版本哈希针对**节点快照**，不是下发的配置文本；账户、限速、路由规则变化都不会体现在版本中。历史清理任务按保留天数（默认 90）与每节点保底版本数（默认 20）分批删除。范围草稿已决定 Go 版只保留最近一次结果，建议改为每台**服务器**记录：`config_sha256`（下发配置全文哈希）、`deployed_at`、`success`、`message`；部署位只保留状态。

### 3.5 失败处理与并发

- 无重试、无回滚（重启失败时新配置已生效于磁盘，服务可能处于停止状态，由 systemd `Restart=on-failure` 兜底）。
- 部署入口之间、部署与限速同步之间**没有服务器级互斥**；两者可能同时写同一台服务器的 `config.json`。Go 版建议对每台服务器加互斥（进程内 `map[serverID]*sync.Mutex` 即可）。
- 部署结果和限速推送失败只记日志（限速推送）或返回给调用方（部署）；过期账户任务汇总后发 Bark 通知。

### 3.6 限速同步路径（另一条下发链路）

`RateLimitService`（`J/service/ratelimit/RateLimitService.java`）：

- 触发：账户新增、修改（`accountNo`/`maxIps`/`downloadMbps`/`uploadMbps` 变化）、删除；账户流量超额与恢复（`J/service/AccountTrafficOverQuotaService.java:51, 101`）。
- 事务提交后才调度（`registerInterposedSynchronization`，`:284-305`）；用 `pending/running` 两个原子标志把并发请求**合并**成下一轮（`:113-146, 307-345`），这是现有唯一的“防抖/合并”机制。
- 限速开关关闭 → 整轮跳过。
- 目标服务器：`findRuntimeTargetServers` = 未禁用、非外部、未过期（`expire_date >= 今天` 或为空），且存在“未禁用节点 + 已部署部署位”（`J/repository/ServerRepository.java:62-72`）。
- 每台服务器：`loadForServer` → `build(ctx, 全部部署位, overrides)` → `CONFIG` + `RELOAD`（`systemctl reload sing-box`，即 `kill -HUP`，`:213-235`），在 `monitorTaskExecutor` 线程池并行。
- **副作用**：按数据库当前状态整机重推，会把尚未部署的节点变更一起下发、并移除待删除部署位的入站，但**不更新**部署位状态和 `node_deployment`。Go 版若合并两条链路，应统一为“整机生成 + 推送 + 记录结果”，部署位状态在任何一次成功整机下发后都更新。

### 3.7 SSH

| 项 | 现状 | 位置 |
|----|------|------|
| 实现 | JSch；连接惰性建立，`createConnection` 不连网，首次操作时连接 | `J/service/ssh/impl/JschConnection.java:283-319` |
| 认证 | `auth_type` = `PASSWORD`（默认）或 `KEY`；`auth` 字段存密码或私钥正文；用户名默认 `root`，端口默认 22 | `J/service/ssh/ServerSshConfigFactory.java:51-84`；`J/model/enums/ServerAuthType.java` |
| 私钥校验 | 统一换行、去 BOM；必须含 `PRIVATE KEY`；含 `ENCRYPTED` 拒绝（不支持口令） | `ServerSshConfigFactory.java:86-102` |
| 主机密钥 | `StrictHostKeyChecking=no`，**不校验**主机密钥 | `JschConnection.java:308` |
| 算法 | 公钥/主机密钥：ed25519、ecdsa-p256/384/521、rsa-sha2-512/256、ssh-rsa | `JschConnection.java:41-57, 307-312` |
| 超时 | 默认 30000 ms；定时任务 10000 ms；连接测试 10000 ms（执行 `echo airopscat-ssh-ok`） | `J/model/dto/SshConfig.java:62-66`；`J/scheduler/ScheduledSupport.java:29-31`；`J/service/ServerService.java:38-39, 433-450` |
| 命令执行 | exec 通道，100 ms 轮询直到通道关闭，无整体超时；收集 stdout/stderr/退出码 | `JschConnection.java:122-160` |
| 文件 | SFTP `put/get` | `JschConnection.java:166-198` |
| 端口转发 | 本地转发 `127.0.0.1:<随机> → 远端 127.0.0.1:101`（gRPC 统计）或 `:19191`（Clash API），用完即关 | `JschConnection.java:205-260`；`SingBoxGrpcQueryClient.java:46` |

**凭据加密**（`J/config/CryptoConverter.java`；`J/util/CryptoUtil.java`）：`server.auth` 经 JPA 转换器加密存储，算法 `AES/ECB/PKCS5Padding`，密钥 = 配置 `airopscat.crypto.secret-key` 的 UTF-8 字节截断/零填充到 32 字节（AES-256），密文 Base64。解密失败时**原样返回**（兼容历史明文）。源码与 `application.properties` 中有默认密钥值（本文不复述）。另：`ServerService.convertToDto` 把解密后的 `auth` 回传给前端（`ServerService.java:240`）。

Go 实现建议：

- 用 `golang.org/x/crypto/ssh`；端口转发用 `client.Dial("tcp", "127.0.0.1:101")` 直接作为 gRPC 的 `WithContextDialer`，无需开本地监听端口。
- 主机密钥采用首次信任（TOFU）并把指纹存库，替代“完全不校验”。
- 数据迁移时用同一算法解密旧 `auth`（兼容“解密失败即明文”），再以 AES-GCM（随机 nonce）重新加密；API 不再回传明文凭据。

---

## 4. 服务器安装与维护脚本

### 4.1 执行方式（`J/service/maintenance/ServerMaintenanceService.java`）

接口：`/api/admin/server-maintenance` 与 `/api/admin/server-installs`（后者是空子类，路由完全相同，`J/controller/ServerInstallController.java:9-14`）。

| 接口 | 行为 |
|------|------|
| `GET /scripts` | 列出脚本目录中文件名匹配 `^(\d+)-(.+)\.sh$` 的文件，按数字前缀、文件名排序；标题/描述取前 12 行的 `# @title:`、`# @description:`，缺省标题由文件名生成（`:52-71, 156-194`） |
| `GET /servers` | 未禁用（`getAllActiveServers`）且非外部的服务器 |
| `GET /scripts/{name}/preview`、`PUT /scripts/{name}` | 读取/覆盖保存脚本内容（保存时校验路径不越出目录、文件名合规，`:77-92`） |
| `POST /execute` `{serverId, scriptName}` | 同步执行一个脚本，返回 `success/exitStatus/stdout/stderr/durationMs` |

执行步骤（`:94-136`）：

1. 读取本地脚本，CRLF→LF。
2. SSH 连接，`mkdir -p '<远端工作目录>'`，SFTP 上传到 `<远端工作目录>/<scriptName>`（执行后**不删除**）。
3. 执行：`chmod +x '<path>' && /bin/bash -lc '<变量定义>; source <path>'`。

注入变量（`:204-216`，均用单引号转义）：

| 变量 | 来源 |
|------|------|
| `AIROPSCAT_STEP_NAME` | 脚本标题 |
| `server_ip` | `server.ip` |
| `server_host` | 服务器主 host（`ServerHostService.resolvePrimaryHost`） |
| `server_ssh_port` | `server.ssh_port`（未 export，但脚本被 `source`，同一 shell 可见） |
| `airopscat_domain` | 系统配置 `airopscat.domain` |
| `airopscat_api_token` | 系统配置 `airopscat.api.token`（明文出现在远端命令行，可被 `ps` 看到） |
| `bandwidth_day` | `server.bandwidth_date` 的日；为空时 `1` |
| `server_hosts` | bash 数组，`ServerHostService.getResolvedHosts`（不可 export，同 shell 可见） |

配置项（`J/service/SystemConfigService.java:277-278`）：

- 脚本目录 `airopscat.install.scripts.dir`，默认 `./config/shell`（相对进程工作目录）；dev profile 为 `./src/main/resources/config/shell`（`R/application-dev.properties:3`）。
- 远端工作目录 `airopscat.install.remote-work-dir`，默认值 `/tmp/airopscat-installer`（占位提示为 `/tmp/airopscat-maintenance`）。

### 4.2 脚本来源与清单

两处脚本目录**互不重叠**：

| 位置 | 脚本 |
|------|------|
| `AirOpsCat/src/main/resources/config/shell/` | `01-system-init.sh`（时区 Asia/Shanghai、apt 基础包、vnstat 保留 90 天、安装 `/usr/local/bin/airopscat-server-monitor-collect`）、`06-guard-agent.sh`（安装账户防共享 agent） |
| `profile/airopscat/shell/`（范围草稿约定的来源） | `02-swap.sh`（2G `/swapfile`）、`03-ssh-key.sh`（**替换** root `authorized_keys` 为硬编码公钥，`PermitRootLogin prohibit-password`，执行后密码登录 root 失效，服务器 `auth_type` 需改为 KEY）、`04-certificates-acme.sh`（acme.sh，Let's Encrypt，默认注册邮箱硬编码，可用 `ACME_EMAIL` 覆盖）、`05-certificates-install.sh`（对 `server_hosts`/`server_host` standalone 签发，安装到 `/usr/local/etc/certs/`）、`07-sing-box.sh`（下载 fork `v1.14.0` amd64 deb，`dpkg -i`，`systemctl enable/restart sing-box`，加日志清理 cron）、`08-sing-box-restart.sh`（每天 05:00 重启 cron）、`09-tcp-optimize.sh`（`/etc/sysctl.d/99-tcp-optimize.conf`，BBR，**禁用 IPv6**）、`10-firewall-no-limit.sh`（iptables 默认 DROP，放行 SSH、80、443、1888、400-500、8000-10000、7304、47326，`iptables-persistent` 持久化）、`11-nginx.sh`（装 nginx、删 default 站点） |

生产实际使用哪个目录取决于部署机上的 `./config/shell` 内容，未确认。

注意事项：

- `NodeService.getAvailablePort` 从 10000 起递增分配端口（`NodeService.java:862-872`），而 `10-firewall-no-limit.sh` 只放行到 10000，10001 及以上的节点端口会被防火墙拦截（现有不一致，Go 版需对齐端口策略）。
- `09-tcp-optimize.sh` 禁用 IPv6，而入站 `listen` 为 `::`（双栈套接字在禁用 IPv6 后能否监听 IPv4，依赖内核 `bindv6only`，未确认会出问题）。
- 01、06 两个脚本写入 `/usr/local/bin`、`/var/run`，不符合 `AGENTS.md` 约定的 `/opt/airopscat/scripts`、`/run/airopscat` 等目录。

### 4.3 Go 实现建议

- 范围草稿已定：只保留“选服务器执行 `profile/airopscat/shell` 脚本”，删除在线编辑。脚本目录用配置项指向仓库检出的 `profile/airopscat/shell`，或在构建时 `embed`。
- 保持变量注入契约（变量名不变），现有脚本无需修改；`airopscat_api_token` 改为经 stdin 或临时 `0600` 文件传递，避免出现在进程列表。
- 执行完删除远端脚本文件；为长时间脚本（apt upgrade）设置可配置超时，并考虑流式输出。

---

## 5. 服务器目录约定与 sing-box 服务

| 路径 / 名称 | 用途 | 依据 |
|-------------|------|------|
| `sing-box.service`（systemd） | `User=sing-box`，`StateDirectory=sing-box`，`ExecStart=/usr/bin/sing-box -D /var/lib/sing-box -C /etc/sing-box run`，`ExecReload=/bin/kill -HUP $MAINPID`，`Restart=on-failure`，`RestartSec=10s` | `SB/release/config/sing-box.service`（随 deb 安装） |
| `/usr/bin/sing-box` | 二进制 | `SingBoxCoreManager.java:17` |
| `/etc/sing-box/config.json` | 唯一配置文件；部署与限速同步覆盖写入 | `SingBoxCoreManager.java:18` |
| `/etc/sing-box/config.json.backup.<epochMillis>` | 每次 CONFIG 前的备份；定时任务“内核配置清理”（cron `airopscat.core.config.cleanup.cron`，默认 `0 0 8 * * ?`）删除 mtime 超过 5 天的备份，仅针对运行目标服务器 | `SingBoxCoreManager.java:66`；`J/scheduler/CoreConfigCleanupTask.java:86-131`；`SystemConfigService.java:368` |
| `/var/lib/sing-box/box.log`、`cache.db` | 日志与缓存；cron 每天 23:00 `truncate` 日志 | 模板；`P/07-sing-box.sh:38-53` |
| `/usr/local/etc/certs/fullchain.cer`、`private.key` | TLS 证书（vless、hysteria2 入站引用） | `P/05-certificates-install.sh:54-79`；入站模板 |
| `/run/airopscat/account-quota.json` | 跨节点配额表，agent 写、内核读 | `SB/common/accountquota/guard.go:44-45`；`06-guard-agent.sh:30` |
| `/usr/local/bin/airopscat-guard-agent.py`、`airopscat-guard-agent.service` | 防共享 agent（读 `/etc/sing-box/config.json` 中的 Clash API 端口与 secret，上报 `https://<airopscat_domain>/api/open/guard-sync`） | `06-guard-agent.sh:20-33, 101, 325-359` |
| `/usr/local/bin/airopscat-server-monitor-collect`、`/var/run/airopscat-monitor-state` | 服务器监控采集 | `01-system-init.sh:64-77` |
| `/var/cache/airopscat` | deb 下载缓存（安装后删除包） | `P/07-sing-box.sh:22-31` |
| `/var/lib/airopscat/backups` | `authorized_keys` 备份 | `P/03-ssh-key.sh:23-41` |
| `/tmp/airopscat-installer` | 维护脚本上传目录（默认） | 4.1 |
| crontab 块 | `# BEGIN/END AIROPSCAT SING-BOX LOG CLEANUP`、`# BEGIN/END AIROPSCAT SING-BOX RESTART` | `P/07-sing-box.sh`、`P/08-sing-box-restart.sh` |

服务管理：AirOpsCat 只通过 `systemctl {start|stop|restart|reload|status} sing-box` 管理；部署用 `restart`，限速同步用 `reload`（SIGHUP）。sing-box 的安装/升级不在控制端代码中，只能经维护脚本 `07-sing-box.sh` 完成。

---

## 6. Go 中可直接复用的部分与判断

### 6.1 可原样复用

| 资产 | 复用方式 |
|------|----------|
| 6 个入站模板 `R/config/core/sing-box-inbound-*.json` | 用 `{placeholder}` 简单替换即可；建议 `embed` 后用 `strings.NewReplacer`，或改为 Go 字面量。行为见 2.3 表 |
| 基础模板 `R/config/core/sing-box.json` | 含 Qute 语法（`{#if}`、`{extraInbounds}`），不能原样作为 JSON。建议去掉条件段落，存成纯 JSON 基底，在 Go 中反序列化为 `map[string]any` 后追加数组 |
| `profile/airopscat/shell/*.sh` 与变量契约 | 原样复用（4.3） |
| v2ray_api 统计 proto | 复制 `AirOpsCat/src/main/proto/traffic_stats.proto`（或 `SB/experimental/v2rayapi/stats.proto`）自行生成 Go 桩；**不要** import `SB/experimental/v2rayapi`，同一环境下 `go list -deps -tags with_v2ray_api` 为 379 行，会拉入 `adapter` 等 sing-box 内部包 |
| 部署位同步、配置生成、限速覆盖的规则 | 按本文第 1、2 节重写，建议以 Java 现有用例为对照写表驱动测试 |

### 6.2 是否 import fork 的 `option` 包

事实：

1. fork 的模块路径仍是 `github.com/sagernet/sing-box`（`SB/go.mod:1`），需 `require github.com/sagernet/sing-box <版本>` + `replace github.com/sagernet/sing-box => github.com/fun90/sing-box <伪版本>`，且要求 `go 1.25.5`（`SB/go.mod:3`）。每次 fork 合并上游都要同步升级 AirOpsCat 的依赖。
2. `option` 包本身较轻：在 fork HEAD `92fe3963`、go1.27.1 darwin/arm64、无额外构建标签下，`go list -deps ./option` 共 231 行（含标准库，数字仅作量级参考），外部依赖只有 `sagernet/sing`、`miekg/dns`、`go4.org/netipx`、`golang.org/x/sys`。
3. 序列化必须用 `sing/common/json` 的 `MarshalContext`（`option.Inbound` 用 `badjson` 合并 `Options`，`SB/option/inbound.go:29-31`）；反序列化带类型的 `option.Inbound`/`option.Options` 需要上下文中的 `InboundOptionsRegistry`（`SB/option/inbound.go:33-45`），该注册表在 `include` 包中，会拉入全部协议实现。
4. `node.inbound` 与 `custom` 路由规则是管理员可编辑的自由 JSON。按协议反序列化到具体结构（如 `option.VLESSInboundOptions`）时，非严格模式会**静默丢弃**未知字段，严格模式（`UnmarshalContextDisallowUnknownFields`）又可能拒绝历史数据。

判断：**不建议在运行时代码中用 `option` 结构体构造配置**，理由：

- 收益仅是十余个字段名的编译期检查（`download_mbps`、`upload_mbps`、`max_ips`、`users`、`tag`、`listen`、`listen_port`、`public_key` 等），而现有算法的核心是“自由 JSON + 少量覆盖”，类型化往返有丢字段风险；
- 把应用发布与 fork 版本、上游 option 结构变化（如 1.13 移除旧入站字段）绑定；
- 远端 `sing-box check` 已经是权威校验，且与线上二进制版本一致。

建议做法：

1. 运行时用 `map[string]any` 生成配置（与现有行为一致），fork 专有字段名定义为常量并注明出处（2.8）。
2. 节点保存时对 `inbound` 做最小校验（合法 JSON、`type` 与协议一致、不含 `tag/listen/listen_port`）。
3. 可选：在**测试**中 import fork 的 `include` + `option`，用 `UnmarshalContextDisallowUnknownFields` 对生成结果做 schema 校验，只能发现字段名/类型错误；tag 引用等构造期校验仍以服务器上的 `sing-box check` 为准；证书文件、端口占用等启动期问题是否会被 `check` 发现未确认，最终以 restart 结果为准。只影响 `go.mod` 与测试依赖，不进入发布二进制。
4. 若仍决定按范围草稿“直接复用 option 包”，至少需要：按协议映射到具体 options 结构、在节点保存时就做严格反序列化（让未知字段在录入时报错而非部署时丢失）、固定 fork 伪版本。

---

## 7. 现有问题清单（重写时决定是否修正）

> 处理决定（2026-10-18，见 `scope.md` 决策 20）：修复 1、3、4、6、7、8、9、10、12；5 仅修复禁用节点，外部服务器保持；2 保持；11 去掉配置项，地址端口固定。

1. 账户增删改在限速关闭时不触发下发（3.1）。
2. 路由规则变化不置待部署（2.5）。
3. 部署与限速同步无服务器级互斥；限速同步会下发未部署变更却不更新状态（3.5、3.6）。
4. 首次部署校验失败时无效配置留在磁盘；`config.json` 非原子写入（3.3）。建议：写临时文件 → `sing-box check -c 临时文件` → `mv` 原子替换 → restart/reload。
5. 禁用节点、外部服务器的部署位在“成功”后被置为已部署（3.4）。
6. 配置预览不含限速覆盖（2.1）。
7. 落地出站会复制服务端 `tls` 原样（含服务端字段），现因协议限制不可达（2.4）。
8. shadowtls 统计用户与入站用户不一致；`max_connections` 未使用；ss/socks 用户不下发限速（2.3、2.8）。
9. SSH 不校验主机密钥；凭据 AES-ECB；API 回传明文凭据；Reality 回退密钥硬编码；随机数非密码学安全（2.3、3.7）。
10. 端口分配起点 10000 与防火墙放行范围不一致（4.2）。
11. Clash API 地址/端口可配置但模板写死（2.6）。
12. 部署在数据库事务内做 SSH（3.2）。

## 8. Go 版 ShadowTLS 完整链路（修正 Java 未完成实现）

- 一个业务节点生成两个入站：外层 `node_<id>_shadowtls` 使用节点公开端口，`detour` 指向内层 `node_<id>`。内层 Shadowsocks 仅监听 `127.0.0.1` 随机 TCP 端口；节点落地路由继续匹配 `node_<id>`。
- 只支持 ShadowTLS v3 + `chacha20-ietf-poly1305`。原 inbound 的 `users` 保留密码作为节点级握手凭据，生成时改用独立内部名称，不参与账户配额或统计；顶层旧 `password` 不下发。外层不再冒充账户身份，避免 detour 前后重复进入账户配额闸门。
- 内层用户来自标签授权的未禁用、未过期账户，`name=account_no`、`password=uuid`，下发用户限速和 IP 限制；流量统计只注册账户名。没有账户时 `managed=true, users=[]`，明确拒绝内层认证；有账户时不能同时设置 managed，否则内核拒绝配置。
- 默认模板开启 `strict_mode=true`，生成随机节点握手密码。旧 v3 模板有 users 时可以沿用握手密码，自动补内层；非 v3、自定义 detour、错误内层参数在保存与生成时拒绝。
- 客户端必须同时配置 Shadowsocks 和 ShadowTLS：sing-box 的 SS 出站 `detour` 指向 ShadowTLS 出站，策略选择 SS；外层密码取节点握手用户，内层密码取账户 UUID。不能沿用 Java 模板的裸 ShadowTLS 输出。
- 当前按 TCP 链路实现和测试，不宣称普通 UDP 可用。各客户端订阅在订阅阶段分别生成其组合格式，不能将不支持的客户端输出为裸 ShadowTLS。
- 真实验证：在用户指定测试服务器的独立临时服务运行生成的服务端及组合客户端，通过 SOCKS 访问 HTTPS 返回 200；日志显示外层节点身份、内层账户身份。清空账户后同一客户端认证失败（`no users`）。测试服务及远端临时文件已清理，原 sing-box 服务仍 active。尚未实测流量统计计数、限速吞吐和非回环客户端源 IP。
