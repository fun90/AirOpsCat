# 订阅输出规格（Go 重写对照）

> 范围：`/subscribe/**` 三个端点与 `/api/open/docs-info/{authCode}`。依据为 Java 现状代码（分支 `rewrite/go`，Quarkus 3.24.4），行号指当前工作区文件。
> 标注说明：**未确认** = 代码中无法直接判定、需抓包或实测；**[实测]** = 已用 qute-core 3.24.4 独立渲染验证（引擎配置与 Quarkus 一致：strict-rendering、remove-standalone-lines、无变体不转义）。

---

## 0. 结论速览（重写必须保持的关键行为）

1. 订阅内容 = **模板文件原样渲染**，模板优先从外部目录读取（生产使用的是 `profile/airopscat/subscription` 那一套，和 classpath 内置版本不同）。Go 版应直接复用同一批模板文件，并实现一个与 Qute 输出逐字节一致的渲染器（或把模板翻译为等价 Go 模板后用金样比对）。
2. 所有输出都**不做 base64**，也**不做 HTML/JSON 转义**；只有显式 `.urlEncode` 的位置做 URL 编码。
3. 模板里引用了不存在的 Map 键时整次渲染失败 → HTTP 400 `错误: 系统内部错误，请联系管理员`。
4. 节点之间的先后顺序在 Java 里来自 `HashSet<Node>` 的迭代顺序（见 3.2），**本身不稳定，不可能逐字节复现**——这是唯一需要明确决策的不兼容点。
5. `timestamp` 是渲染时的毫秒时间戳，每次请求都不同；逐字节比对时需要屏蔽这一行。

---

## 1. 端点

所有 `/subscribe/*` 与 `/api/open/*` 都免登录（`application.properties:59` `quarkus.http.auth.permission.public.paths=...,/subscribe/*,/api/open/*,...`）。

### 1.1 `GET /subscribe/config/{authCode}/{osName}/{appName}` 与 `GET /subscribe/config/{authCode}/{osName}/{appName}/{remarkName}`

`controller/SubscriptionController.java:32-119`

| 参数 | 位置 | 说明 |
|------|------|------|
| `authCode` | 路径 | 账户认证码，精确匹配 `account.auth_code` |
| `osName` | 路径 | 只作为模板变量 `osName` 传入，现有模板都没有使用；任意值都可以 |
| `appName` | 路径 | 决定模板文件名，见下表；**区分大小写**（文件名直接拼接） |
| `remarkName` | 路径，可选 | **完全忽略**（不带它的重载固定传 `"default"`，也没有被使用）。它存在的意义只是让 Shadowrocket 拿 URL 最后一段当配置名 |
| `version` | Query | 等于 `"1"` 时模板变量 `dns` 取 `dns2`，否则取 `dns`（`:61-69`） |
| `dns` / `dns2` | Query | 有值时才放入模板变量 `dns`；参数不存在时不放入（模板用 `{dns ?: ...}` 兜底） |
| `view` | Query | 等于 `"1"` 时不发送 `Content-Disposition` |

`appName` → 模板文件（`service/SubscriptionService.java:234-245`）：
- 与 `loon` 或 `shadowrocket` 忽略大小写相等时，后缀为 `.conf`
- 与 `sing-box` 忽略大小写相等时，后缀为 `.json`
- 其余情况后缀为 `.yaml`
- 文件名 = `appName` 原样拼接后缀，路径为 `config/subscription/<appName><后缀>`

当前可用的 appName（生产外部目录）：`clash-meta`、`clash-meta2`、`clash-mi`、`clash-verge`、`clash-verge-mrs`、`stash`、`loon`、`shadowrocket`、`sing-box`。`shadowrocket.yaml` 实际不可达，因为 `shadowrocket` 一律走 `.conf`。大小写不同（如 `Loon`）时后缀判断能命中，但在区分大小写的文件系统上找不到 `Loon.conf`，结果是 400 系统内部错误。

**成功响应**（`:89-111`）：
- 状态码：200
- `Content-Type: text/plain`（`@Produces(TEXT_PLAIN)` + `.type(TEXT_PLAIN)`）。Quarkus REST 是否追加 `;charset=UTF-8`：**未确认**，以抓包为准
- 自定义头 `charset: UTF-8`（字面量就是一个叫 `charset` 的响应头）
- `profile-update-interval: 72`
- 当 `view != "1"` 时：`Content-Disposition: attachment;filename*=UTF-8''<enc>`
  - `<enc>` = Java `URLEncoder.encode(account.remark, UTF-8)`：空格编码为 `+`（**不**替换成 `%20`）；`A-Z a-z 0-9 . - * _` 保持原样，其余字符按 UTF-8 字节编码为 `%XX`（大写十六进制）
  - remark 为 null 时 NPE → 400 系统内部错误
- `subscription-userinfo`：
  - appName 忽略大小写等于 `shadowrocket` 时：`tfc: <G>G; exp: <yyyy/MM/dd>`
    - `<G>` = `BigDecimal(usedFlow) / 1073741824`，保留 2 位小数，`HALF_UP` 舍入，`toPlainString`（例如 `0.00`、`12.35`）
    - `exp` = `toDate` 按 `yyyy/MM/dd` 格式化
  - 其余 appName：`download=<usedFlow>; total=<totalFlow>; expire=<epochSeconds>`
    - 没有 `upload=` 字段；`download` 是上行与下行之和
    - `expire` = `toDate.toEpochSecond(ZoneOffset +08:00)`，即把 `toDate` 当作北京时间解释
  - `usedFlow` / `totalFlow` 的计算见 2.3
- 已注释、未发送：`profile-web-page-url`（`:108-109`）

**失败响应**：全部为 HTTP 400、`text/plain`，正文 `错误: <message>`：

| 条件 | message | 出处 |
|------|---------|------|
| authCode/osName/appName 去空格后为空（例如 `%20`） | `认证码不能为空` / `操作系统名称不能为空` / `应用名称不能为空` | `SubscriptionService.java:142-150` |
| 找不到 authCode | `无效的认证码，账户不存在` | `:153-156` |
| 账户被禁用**或已过期** | `账户已被禁用，请联系管理员` | `:161-163`，见 2.1 |
| （实际不可达）已过期 | `账户已过期，请续费后重试` | `:166-168` |
| 没有可用节点 | `当前账户没有可用的节点，请联系管理员` | `:171-173` |
| 渲染结果为 null（实际不可达） | `生成配置文件失败，不支持的应用类型: <appName>` | `:181-183` |
| `content == null`（实际不可达） | `生成配置文件失败` | Controller `:82-87` |
| 任何异常：模板文件不存在、Qute 引用缺失键、`toDate` 为 null、`remark` 为 null（view≠1 时）、流量字段为 null 导致的 NPE 等 | `系统内部错误，请联系管理员` | Controller `:112-118`，同时 `log.error` |

注意：**`toDate` 为 null（长期有效）的账户必然失败**，因为生成头时 `getExpireDate().format(...)` / `toEpochSecond(...)` 会 NPE（`:101`、`:104`）。这是现有行为。

### 1.2 `GET /subscribe/nodes/{authCode}/{appType}`

`SubscriptionController.java:134-145`，`SubscriptionService.java:94-135`。供 Loon 的 `[Remote Proxy]` 使用（`loon.conf` 中的 `{subscriptionUrl}/nodes/{account.authCode}/loon`）。

- `appType` → 模板 `config/subscription/nodes/<appType>.html`；现有取值 `loon`、`shadowrocket`
- 账户校验、节点选取、混淆规则与 config 端点相同，但**所有业务错误都返回 200**，正文分别为 `错误: 无效的认证码，账户不存在` / `错误: 账户已被禁用，请联系管理员` / `错误: 账户已过期，请续费后重试`（不可达）/ `错误: 当前账户没有可用的节点，请联系管理员`
- 后处理：先执行 `result.replaceAll("(?m)^\\s*$[\n\r]+", "")` 删除空白行，再 `trim()`（`:134`）。因此输出**末尾没有换行**，行之间用 `\n` 分隔
- 响应头：`Content-Type: text/plain`、`charset: UTF-8`；**没有** `subscription-userinfo`、`profile-update-interval`、`Content-Disposition`
- 模板变量：`account`、`nodes`、`subscriptionUrl`、`timestamp`（没有 `osName`、`appName`、`dns`）
- 异常（模板不存在、缺失键）不在 try 内，由 `GlobalExceptionMapper`（`config/GlobalExceptionMapper.java:61-67`）处理 → **HTTP 500、`text/html`**，正文是 `error/500` 页面

### 1.3 `GET /subscribe/rules/{appType}/{ruleName}`

`SubscriptionController.java:121-132`，`SubscriptionService.java:81-92`

- 读取 `config/subscription/rules/<appType>/<ruleName>`，**原样返回，不经过模板渲染**
- `ruleName` 包含扩展名，例如 `myproxy.yaml`、`hst.json`、`football.list`
- `appType` 只是目录名，与客户端没有映射关系。现有目录：`clash`（Clash 系和 Shadowrocket 共用）、`sing-box`、`loon`（仅外部目录有 `sing-box`、`loon`）
- 响应：200、`text/plain`、`charset: UTF-8`
- 空参数检查（`错误: 应用类型不能为空` / `错误: 规则名称不能为空`，200）在路由层面不可达
- 文件不存在：`ConfigFileReader` 抛 `IllegalArgumentException` → `GlobalExceptionMapper` → **500 HTML**（不是 404）
- 路径穿越：参数只占一个路径段；编码后的 `%2F`、`..` 能否穿透：**未确认**。建议 Go 版只允许单段文件名（拒绝 `/`、`\`、`..`），对正常客户端没有影响

### 1.4 其他路由行为（框架层）

- 未匹配路径 → `NotFoundException` → 404 `text/html`（`GlobalExceptionMapper.java:35-42`）
- 非 GET 方法 → 405（`WebApplicationException`，原样返回）；是否自动支持 `HEAD`：**未确认**
- `docs-info` 生成的 URL 末尾带 `/`（见第 6 节），客户端会带着这个 `/` 来请求。Quarkus 能匹配末尾斜杠（生产在用）；Go 路由**必须同时接受有和没有末尾 `/` 的路径**
- 全局响应头（`application.properties:85-89`，作用于所有路由）：`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、`X-XSS-Protection: 1; mode=block`、`Strict-Transport-Security: max-age=31536000; includeSubDomains`、`Referrer-Policy: strict-origin-when-cross-origin`
- CORS 已开启，origins=`https://yourdomain.com`（`:91-96`）

---

## 2. 账户校验与流量

### 2.1 查找与状态

- `accountRepository.findByAuthCode` = `find("authCode", authCode).firstResultOptional()`（`repository/AccountRepository.java:43-45`）。`auth_code` 列不是唯一键，重复时取数据库返回的第一条（没有 order by）
- `Account.isActive()` = `!isExpired() && (disabled == null || disabled == 0)`；`isExpired()` = `toDate != null && now.isAfter(toDate)`（`model/entity/Account.java:103-114`）
- 因此**过期账户在 `isActive` 这一步就被拦下，返回的是"账户已被禁用"**；随后的"已过期"分支条件与之相同，仅在两次 `now()` 恰好跨过 `toDate` 的瞬间才可能命中。Go 版保持"过期 → 账户已被禁用，请联系管理员"即可
- 没有流量超额检查：超额账户照常下发订阅（超额限制由别处负责）
- 时间比较使用 JVM 本地时区的 `LocalDateTime.now()`；原生镜像的 systemd 启动参数为 `-Duser.timezone=Asia/Shanghai`（`src/main/assembly/bin/setup-systemd.sh:135`），JVM 模式没有设置（:97）。Go 版统一按 `Asia/Shanghai` 处理

### 2.2 `account.level` 与 `node.level`

订阅链路**完全没有使用** `level`。重写范围草稿已决定删除 `level`。

### 2.3 流量头的取值（`SubscriptionService.java:186-193`）

```
quotaGB  = getEffectiveBandwidth(accountId)
           // 当前周期统计(account_traffic_stats where account_id=? and period_start <= now and period_end > now)
           // 第一条记录的 bandwidth_quota；为 null 或无记录时取 account.bandwidth；仍为 null 则为 null
totalFlow = (quotaGB ?? 500) * 1024^3                       // 不限量时按 500G 显示
stats     = 同一查询的第一条记录（再查一次，now 取值不同）
usedFlow  = stats ? stats.upload_bytes + stats.download_bytes : 0   // 任一为 null 时 NPE → 400
```

`AccountTrafficStatsService.java:195-205`，`AccountTrafficStatsRepository.java:54-56`。"第一条"没有排序（多条重叠周期时结果不确定）。

---

## 3. 节点选取算法

入口：`SubscriptionService.loadAvailableSubscriptionNodes`（`:255-315`）。

### 3.1 候选节点

1. `TagService.getAvailableNodesByAccount(accountId)`（`service/TagService.java:287-299`）：
   - 账户标签：`SELECT t.* FROM tag t JOIN account_tag at ON t.id=at.tag_id WHERE at.account_id=?`（`TagRepository.java:33-40`）。**不过滤 `tag.disabled`**
   - 对每个标签：`select n from Node n join n.tags t where t.id=?`（关联表 `node_tag`）
   - 全部放入 `HashSet<Node>` 去重，再转成 `ArrayList`
2. 过滤（`:256-260`）：`node != null`、`node.disabled != 1`（null 视为启用）、`node.type == 0`（PROXY；落地节点 type=1 排除）
3. 收集到以 node.id 为键的 `LinkedHashMap`，**保持第 1 步 HashSet 的迭代顺序**

### 3.2 节点顺序（不兼容点）

`Node` 带 Lombok `@Data`（`model/entity/Node.java:13`），`hashCode` 由所有字段计算（id、port、protocol、inbound 字符串、name、remark、region、createTime、updateTime、tags、outNode 等），`HashSet` 的迭代顺序取决于这些哈希值。结果：
- 顺序与 id、名称、优先级都无关，修改节点的任意字段（包括 updateTime）都可能改变顺序
- Go 无法复现这个顺序

**需要决定**：建议 Go 版采用确定的顺序（例如 `node.id` 升序），并把这一项作为逐字节比对中唯一允许的差异（比对前按节点块排序，或者用节点数 ≤1 的账户比对）。各客户端都按名称引用节点，顺序变化不影响功能，只影响列表展示顺序。

### 3.3 部署位与快照

```
nodeIds    = 候选节点 id（顺序同上）
placements = node_server where node_id in nodeIds order by priority, id    // NodeServerRepository.java:28-33
             过滤 deployed == 1（null/未知按 0 处理 → 排除）                 // NodeDeploymentStatus.isDeployed
snapshots  = node_deployment where node_id in nodeIds；
             snapshot_json 为空白、或 node_server_id 为 null 的跳过；
             以 node_server_id 为键（该列唯一）                              // :268-274, NodeDeployment.java:18
若 snapshots 为空 → 返回空列表（即"没有可用节点"）
servers    = server where id in {snapshot.serverId}                          // 注意：用快照里的 serverId
```

每个"已部署且有快照"的部署位生成一个候选条目 `NodeDto`（`toSubscriptionNodeDto`，`:387-411`），字段来源：

| 模板字段 | 来源 |
|----------|------|
| `name` | **当前** `node.name`（不是快照里的 name），经 `NodeName.withNumber` 处理，见 3.5 |
| `port`、`protocol`、`coreType`、`type`、`inbound`、`rule`、`level`、`disabled`、`remark` | 快照 JSON（`NodeDeploymentVersionSnapshotDto`） |
| `inbound` | 快照里的 `inbound`，Jackson 反序列化成 `Map<String,Object>`，模板按键路径访问 |
| `serverHost` | `resolveEffectiveHost(placement.accessHost, server)`：部署位的域名记录存在、`status == SYNCED` 且 `full_name` 非空白时用 `full_name`，否则用 `server.ip`；server 不存在时为 null（`NodeConverter.java:156-162`）。随后可能被账户绑定覆盖（3.4） |
| `serverIp` | `server.ip` |

`placement.accessHost` 是当前部署位（`node_server.access_host_id` → `domain_dns_record`）的值，不是快照里的值。

`serverHost` 为 null 时，模板中 `{node.serverHost}` 渲染为**空字符串** [实测]。

### 3.4 每个节点输出哪些条目（`selectSubscriptionEntries`，`:354-378`）

先读取账户的订阅定制（`AccountNodeSubscriptionDomainBindingService.java:165-208`）：
- `bindings` = `account_node_subscription_domain_binding where account_id=? and node_id in (?) and enabled = 1`（每个账户+节点最多一条，唯一键 `uk_account_node_subscription_domain`）
- `hostOverride[nodeId]` = 绑定的 `domain_dns_record_id` 对应记录存在、`status == SYNCED`、`trim(full_name)` 非空时取 `trim(full_name)`
- `selected[nodeId]` = 绑定的部署位集合（表 `account_node_subscription_placement(binding_id, node_server_id)`），集合为空时视为没有定制

然后对每个候选节点（按 3.2 的顺序）：

```
customized = selected[nodeId] 非空
sorted = 候选条目
         过滤：customized ? placement.id ∈ selected : 全部
         排序：priority 升序（null 视为 +∞），再按 placement.id 升序（null 视为 +∞）
if sorted 为空: 输出 0 条
elif hostOverride 非空白:
    输出 1 条 = sorted[0]，serverHost 改为 hostOverride        // 不检查 exposed
elif customized:
    输出 sorted 全部                                           // 不检查 exposed
else:
    输出 sorted 中 exposed 为 null 或 1 的条目                  // NodeServer.exposedFlag()
```

边界情况：如果某个绑定只有域名、没有部署位（`customized=false` 但有 `hostOverride`），会取全部已部署部署位中优先级最高的那一个，**即使它的 exposed=0**。

### 3.5 命名、去重与混淆

`util/NodeName.java`：正则 `NUMBER = -(\d+)$`
- `withNumber(name)`：null/空白原样返回；否则 `trim`，以 `-数字` 结尾就保持不变，否则追加 `-1`
- `base(name)`：先 `withNumber`，再去掉结尾的 `-数字`
- `number(name)`：结尾数字（BigInteger）；null/空白返回 1

去重 `ensureUniqueNames`（`SubscriptionService.java:322-343`），在混淆**之前**执行：
```
byPlacement = 按 nodeServerId 升序（null 排最后）的稳定排序副本     // 只用于决定谁改名，不改变输出顺序
used = {}; dups = []
for n in byPlacement: if !used.add(n.name): dups.append(n)
for n in dups:
    base, k = base(n.name), number(n.name)
    repeat: k += 1; cand = base + "-" + k
    until used.add(cand)
    n.name = cand
```

混淆 `NodeObfuscator.obfuscate`（`util/NodeObfuscator.java:26-62`），仅当 `account.node_multiple != null` 时执行（`SubscriptionService.java:176-178`）：
```
prefix   = node_prefix ?? ""
multiple = clamp(node_multiple, 1, 10)            // 0 或负数也会按 1 处理，同样会改名
for node in nodes（保持顺序）:
    start = number(node.name) * 10
    for i in 0..multiple-1:
        输出 node 的副本，name = base(node.name) + "-" + prefix + (start + i)
```
例：`香港-2`，multiple=3，prefix=`x` → `香港-x20`、`香港-x21`、`香港-x22`。副本的其余字段完全相同（同一服务器、同一端口）。

---

## 4. 模板渲染

### 4.1 模板来源（`util/ConfigFileReader.java:42-136`）

每次请求都重新读取文件（缓存代码已注释），每次都重新 `engine.parse`。查找顺序：
1. 外部目录：`templatesDir` = 系统配置 `airopscat.config.templates.dir`（先查 `system_config` 表，再查配置文件，默认 `./config`，见 `SystemConfigService.java:276`）。逻辑路径 `config/subscription/X` 去掉 `config/` 前缀后拼成 `<templatesDir>/subscription/X`。相对路径相对于进程工作目录（systemd `WorkingDirectory=/opt/airopscat`，即默认 `/opt/airopscat/config/subscription/`）
2. 不存在（或不是普通文件）时读 classpath `config/subscription/X`（`src/main/resources/config/subscription/`）
3. 两处都没有 → `IllegalArgumentException("Configuration file not found: ...")`

**生产实际使用的是外部目录**，其内容来自 `/Users/omg/Documents/Code/VPN/profile/airopscat/subscription/`（独立 Git 仓库）。它与 classpath 版本差异很大（多出 HST/Broker/Football/GitHub 分组、`nodes.size > 1` 条件、`#capture`、额外规则集、`fingerprint=Safari` 等），classpath 版本已经过时。仓库里没有把 profile 同步到服务器的脚本，同步方式**未确认**（推测为手工复制）。Go 版应以 profile 目录为准，保留"外部目录优先、内置兜底"的机制。外部目录独有的文件：`clash-meta2.yaml`、`clash-verge-mrs.yaml`、`shadowrocket.yaml`、`rules/clash/{football,hk-broker,hst,hst-domain}.yaml`、`rules/loon/football.list`、`rules/sing-box/*.json`。

`subscriptionUrl` = 系统配置 `airopscat.subscription.url`（默认 `http://localhost:8080/subscribe`，经 trim，空字符串视为 null）。`SystemConfigService` 把解析结果缓存在内存中，在控制台保存时更新（`SystemConfigService.java:143-164`）。

### 4.2 模板数据

| 变量 | config 端点 | nodes 端点 | 类型/说明 |
|------|:-:|:-:|-----------|
| `account` | ✓ | ✓ | Account 实体；模板只用到 `account.uuid`、`account.authCode` |
| `nodes` | ✓ | ✓ | `List<NodeDto>`，顺序见第 3 节 |
| `subscriptionUrl` | ✓ | ✓ | 字符串，可能为 null（渲染为空字符串） |
| `timestamp` | ✓ | ✓ | `System.currentTimeMillis()`，long |
| `osName`、`appName` | ✓ | | 路径参数原样 |
| `dns` | 仅当传入时 | | 见 1.1 |

节点字段：`node.name`、`node.serverHost`、`node.port`、`node.protocol`、`node.coreType`、`node.inbound.*`。模板中用到的 inbound 键路径：
`tls.server_name`、`tls.reality.public_key`、`tls.reality.short_id[0]`、`obfs.password`、`version`、`handshake.server`、`streamSettings.realitySettings.serverNames[0]` / `publicKey` / `shortIds[0]`（最后一组只在 `coreType=='xray'` 分支中出现，属于遗留路径）。

### 4.3 Qute 语义（Go 渲染器必须复现）

引擎配置：`application.properties:104-106`（`property-not-found-strategy=throw-exception`、`remove-standalone-lines=true`、`strict-rendering=true`）；`util/TemplateUtil.java:20-35` 使用 `engine.parse(content)`，没有指定变体。

1. **不转义**：`HtmlEscaper` / `JsonEscaper` 只在模板有 content-type 变体时生效（qute-core `HtmlEscaper.java:69-78`、`JsonEscaper.java:176-188`），`engine.parse(String)` 的变体为 null。所以 `sing-box.json` 中节点名里的 `"` 会原样输出、产生非法 JSON，YAML 中的特殊字符也不加引号 [实测]
2. **独立行删除**：如果一行只包含段标签（`{#for}`、`{#if}`、`{#else}`、`{/if}`、`{/for}`、`{#capture}` 等）和空白、不包含任何 `{表达式}`，这一行连同换行符整体删除（qute-core `Parser.java:215-230`、`:1170-1187`）。只要行内有表达式或非空白文本，空白就原样保留。例如生产 `sing-box.json` 中：
   - `  {#capture autoNodes}{#for ...}"{node.name}",{/if}{/for}{/capture}` 这一行因为包含表达式，不算独立行 → 输出两个空格加换行（文件开头 `{` 之后会出现两行 `  `）[实测]
   - `        {#if nodes.size > 1}"🎈 自动选择",{/if}` 条件为假时输出 8 个空格加换行 [实测]
3. **null 值** → 空字符串 [实测]
4. **Map 中缺失的键** → `TemplateException` → 整次请求失败 [实测]（qute-core `ValueResolvers.java:762-763`：键存在但值为 null 时得到 NULL，键不存在得到 NotFound）。只有实际执行到的分支才会求值，例如 vless 节点不会访问 `obfs.password`
5. `{a ?: 'x'}` / `{a ?: "x"}`：a 未定义或为 null 时输出 `x`
6. `list[0]` 取索引（Quarkus `CollectionTemplateExtensions.getByIndex`）；越界：**未确认**（推测抛异常 → 失败）
7. 迭代元数据：`{#for node in nodes}` 内的 `node_hasNext`（是否还有下一个元素）
8. `nodes.size`：列表长度
9. `{#capture id}...{/capture}` 等同于 `{#fragment id rendered=false}`：原位置不输出，通过 `{capture:id}` 在其他位置输出渲染结果字符串；`{capture:id.replaceAll(',$','')}` 是对结果调用 Java `String.replaceAll`，即去掉末尾的一个逗号
10. `node.name.contains('美国')`：Java `String.contains`
11. `==`、`&&`、`||`、括号：字符串相等比较；`node.coreType` 为 null 时 `== 'sing-box'` 为假
12. 数字（port、`inbound.version`）按 Java `toString` 输出（整数没有小数点；Jackson 把 JSON 整数读成 Integer/Long）
13. 模板末尾有无换行原样保留（例如外部 `clash-verge.yaml` 末尾没有换行）

**`.urlEncode`**（`util/QuteTemplateExtensions.java:13-19`）：null → `""`；否则 `URLEncoder.encode(String.valueOf(v), UTF_8).replace("+", "%20")`。等价规则：`[A-Za-z0-9.\-*_]` 原样保留，空格 → `%20`，其他字符按 UTF-8 字节 → `%XX`（大写）。**注意 Go 的 `url.QueryEscape` / `PathEscape` 都不等价**（Go 会编码 `*`、保留 `~`），需要自行实现。

建议：Go 版用 Java 生成金样（多协议、多节点、单节点、带 dns/不带 dns、节点名含空格/中文/引号/`*~` 等），对 Go 输出逐字节比对；比对时屏蔽 `# Generated at:` 这一行。验证用的独立渲染器可参考本次使用的方法（qute-core 3.24.4 + mutiny 2.9.2 + jboss-logging 3.6.1，`Engine.builder().addDefaults()` + `ReflectionValueResolver` + strict + removeStandaloneLines + `FragmentNamespaceResolver("capture")` + 自定义 urlEncode/索引解析器）。

### 4.4 各客户端、各协议的节点表示（以生产外部模板为准）

"✗" 表示该协议节点在 proxies/[Proxy] 中不输出。**分组里的节点列表大多数是对 `nodes` 不加协议过滤地遍历**，因此被跳过的协议节点仍可能出现在分组中，形成悬空引用（现有行为，保持即可）。

#### Clash 系（`clash-meta/clash-mi/clash-verge/clash-meta2/clash-verge-mrs/stash.yaml`）

`proxies:` 段（每行缩进见原文件）：
- `vless` / `vless-reality`：
  ```yaml
    - name: {name}
      type: vless
      server: {serverHost}
      port: {port}
      uuid: {uuid}
      network: tcp
      tls: true
      udp: true
      flow: xtls-rprx-vision
  # vless-reality 且 coreType=sing-box：
      servername: {inbound.tls.server_name}
      reality-opts:
        public-key: {inbound.tls.reality.public_key}
        short-id: {inbound.tls.reality.short_id[0]}
  # vless-reality 且 coreType=xray：上面三项改为 streamSettings.realitySettings.serverNames[0]/publicKey/shortIds[0]
  # vless-reality 且 coreType 为其他值：这里不输出任何内容
  # vless：
      skip-cert-verify: true
  # 都输出：
      client-fingerprint: chrome
  ```
- `hysteria2` 且 coreType=`sing-box`：`name,type: hysteria2,server,port,password: {uuid},obfs: salamander,obfs-password: {inbound.obfs.password},sni: {inbound.tls.server_name},skip-cert-verify: true,client-fingerprint: chrome,alpn: [- h3],udp: true`（逐行，顺序如上）
- hysteria2（非 sing-box）、shadowtls、shadowsocks、socks：✗
- 分组：使用同一协议过滤条件 `vless || vless-reality || (hysteria2 && sing-box)`；"自动"分组额外要求名称包含 `美国|新加坡|日本`，Football 分组要求名称包含 `新加坡|日本`；`{#if nodes.size > 1}` 控制"自动"分组及其引用是否出现
- 规则集 URL：`{subscriptionUrl}/rules/clash/<name>.yaml`（外部模板中 `hst` 这一项的 url 没有加引号）

#### Shadowrocket（`shadowrocket.conf`，完整配置，**不是 base64 节点列表**）

`[Proxy]` 每节点一行（`, ` 分隔，值不做编码）：
- hysteria2（**不检查 coreType**）：`{name} = hysteria2, {host}, {port}, auth={uuid}, peer={inbound.tls.server_name}, obfs=salamander, obfsParam={inbound.obfs.password}, udp=true, alpn=h3, skip-cert-verify=true`
- vless：`{name} = vless, {host}, {port}, password={uuid}, tls=true, sni={host}, flow=xtls-rprx-vision, udp=true, skip-cert-verify=true, fingerprint=Safari`
- vless-reality：`{name} = vless, {host}, {port}, username={uuid}, tls=true, sni={inbound.tls.server_name}, security=reality, pbk={inbound.tls.reality.public_key}, sid={inbound.tls.reality.short_id[0]}, h2=true, fingerprint=Safari, flow=xtls-rprx-vision`（不区分 coreType，固定使用 sing-box 的键路径）
- 其他协议：✗

`[Proxy Group]` 用 `{#for node in nodes}, {node.name}{/for}` 拼接，**不按协议过滤**。

#### Shadowrocket 节点端点（`nodes/shadowrocket.html`，每行一个 URI，纯文本，不做 base64）

- hysteria2：`hysteria2://{uuid|ue}@{host|ue}:{port}?insecure=1&sni={inbound.tls.server_name|ue}&obfs=salamander&obfs-password={inbound.obfs.password|ue}#{name|ue}`
- vless：`vless://{uuid|ue}@{host|ue}:{port}?encryption=none&security=tls&sni={host|ue}&type=tcp&flow=xtls-rprx-vision#{name|ue}`
- vless-reality：`vless://{uuid|ue}@{host|ue}:{port}?encryption=none&security=reality&sni={sni|ue}&pbk={pbk|ue}&sid={short_id[0]|ue}&type=tcp&h2=true&fp=chrome&flow=xtls-rprx-vision#{name|ue}`
- 其他协议：✗

（`|ue` 表示 `.urlEncode`，参数顺序固定如上）

#### Loon（`loon.conf`；节点通过 `[Remote Proxy]` 引用 `/subscribe/nodes/{authCode}/loon`，`authCode` 不做编码）

`nodes/loon.html`：
- hysteria2：`{name}=Hysteria2,{host},{port},"{uuid}",skip-cert-verify=true,sni={inbound.tls.server_name},udp=true,fast-open=true,salamander-password={inbound.obfs.password}`
- vless：`{name}=VLESS,{host},{port},"{uuid}",transport=tcp,flow=xtls-rprx-vision,over-tls=true,sni={host},skip-cert-verify=true,udp=true`
- vless-reality：`{name}=VLESS,{host},{port},"{uuid}",transport=tcp,flow=xtls-rprx-vision,over-tls=true,sni={inbound.tls.server_name},public-key="{inbound.tls.reality.public_key}",short-id={inbound.tls.reality.short_id[0]},skip-cert-verify=true,udp=true`
- 其他协议：✗

`loon.conf` 本身只用到 `subscriptionUrl`、`account.authCode`、`account.uuid`、`timestamp`、`nodes.size`。

#### sing-box（`sing-box.json`）

outbounds 中每节点一个对象，`{#if node_hasNext},{/if}` 控制逗号：
- hysteria2 且 sing-box：`tag,type: hysteria2,server,server_port,password: uuid,obfs{type: salamander,password: inbound.obfs.password},tls{enabled: true,insecure: true,server_name: inbound.tls.server_name,alpn: ["h3"]}`
- shadowtls 且 sing-box：`tag,type: shadowtls,server,server_port,version: {inbound.version}(不加引号),password: uuid,tls{enabled: true,insecure: true,server_name: inbound.handshake.server}`
- **其余所有情况（包括 shadowsocks、socks、非 sing-box 的 hysteria2）都按 vless 输出**：`tag,type: vless,server,server_port,uuid,tls{enabled: true,insecure: true,[reality 分支或 server_name],utls{enabled: true,fingerprint: chrome}},flow: xtls-rprx-vision`
  - vless-reality + sing-box：`server_name: inbound.tls.server_name, reality{enabled: true,public_key,short_id: short_id[0]}`
  - vless-reality + xray：使用 streamSettings 键路径
  - vless-reality + 其他 coreType：两者都不输出
  - 非 vless-reality：`server_name: {serverHost}`
- 分组：`🚀 节点选择` 等使用不过滤的遍历；`🎈 自动选择` 来自 `capture:autoNodes`（名称包含 美国/新加坡/日本，去掉末尾逗号）；`⚽ Football` 来自 `capture:footballNodes`（不去掉逗号，后面紧跟 `"🎯 全球直连"`）
- `hstDns.server` = `{dns ?: '119.29.29.29'}`
- 注意：classpath 旧版 `sing-box.json:177` 有一个多余逗号（`},` 后面接 `"flow"`）；生产外部版已修正

---

## 5. 规则文件

见 1.3。内容按字节原样返回（例如 `rules/clash/myproxy.yaml` 末尾没有换行）。客户端引用关系：Clash 系与 Shadowrocket 引用 `rules/clash/*.yaml`，sing-box 引用 `rules/sing-box/*.json`（`format: source`），Loon 引用 `rules/loon/football.list`。

---

## 6. `GET /api/open/docs-info/{authCode}`

`controller/OpenController.java:71-132`；`@Produces(APPLICATION_JSON)`，Jackson 配置 `NON_NULL`（`config/JacksonConfig.java:24`）→ **值为 null 的字段不输出**。外部文档站 `vpn-docs/v2/index.html:677-679,1025-1089` 使用此接口。

- 账户查找：authCode 为空白或找不到、`isActive()` 为假（禁用或过期）→ **400**，`{"error":"无效的认证码，账户不存在"}`
- 成功 200：
```json
{
  "subscriptionUrl": {
    "windows": "<U>/config/<authCode>/windows/clash-verge/",
    "linux":   "<U>/config/<authCode>/linux/clash-verge/",
    "ios":     "<U>/config/<authCode>/ios/shadowrocket/<URLEncoder(remark)>/",
    "macos":   "<U>/config/<authCode>/macos/clash-verge/",
    "android": "<U>/config/<authCode>/android/clash-meta/"
  },
  "appleId":  "<系统配置 airopscat.apple.id，null 时省略>",
  "applePwd": "<系统配置 airopscat.apple.pwd，null 时省略>",
  "nickName": "<account.remark，null 时省略>",
  "usage": {
    "usedBytes": 0,                  // 当前周期 upload+download（null 按 0），无记录为 0；始终输出
    "totalBytes": 0,                 // 有效配额GB*1024^3；不限量时为 null → 省略（与订阅头的 500G 兜底不同）
    "resetDate": "yyyy-MM-dd",       // 当前周期 period_end；没有时省略
    "expireDate": "yyyy-MM-dd",      // account.to_date；null 时省略
    "remainingDays": 0               // max(0, days(today → toDate 的日期部分))；toDate 为 null 时省略
  }
}
```
- `<U>` = `airopscat.subscription.url`；authCode 不做编码，直接拼接；URL 末尾都有 `/`（`SubscriptionService.java:216-225`）。ios 的 remark 使用 `URLEncoder`（空格编码为 `+`）；remark 为 null 时 NPE → 500 HTML
- `getConfigUrl` 在 `account.uuid == null` 时返回 null，`Map.of` 遇到 null 会抛 NPE → 500（uuid 列 NOT NULL，实际不会发生）
- 键顺序：`HashMap` / `Map.of` 的迭代顺序（`Map.of` 每次 JVM 启动都会随机化）→ 不是契约，消费方按键解析
- `Content-Type`：`application/json`，是否带 charset：**未确认**

---

## 7. 副作用：缓存、压缩、日志

- **缓存**：订阅结果没有缓存；模板文件每次读取（`ConfigFileReader` 缓存已注释，`:47-51`、`:67-68`），每次都重新 parse。系统配置值（subscription.url、templates.dir、request-log.*）有进程内缓存，控制台保存时刷新
- **gzip**：`quarkus.http.enable-compression=true`（`application.properties:84`）。客户端发送 `Accept-Encoding` 时，按 Quarkus 默认的 `compress-media-types` 列表（包含 `text/plain`、`application/json`）进行压缩：**未确认**（以抓包为准）。客户端都能处理 gzip，Go 版对这两种类型启用 gzip 即可
- **请求日志**：`config/SystemRequestLogFilter.java` 在 Vert.x 路由 order -1000 记录所有请求；`SystemRequestLogService.record` 只保存路径匹配 `airopscat.request-log.paths`（默认 `/api/open/docs-info/*,/subscribe/*`，`/*` 结尾按前缀匹配，其余精确匹配）的请求，写入 `system_request_log` 表：`access_time, method, request_path(512), query_string(1024), client_ip(128, 优先 X-Forwarded-For 第一段 → X-Real-IP → 远端地址 → "unknown"), status_code, duration_millis, user_agent(512), referer(512), created_at`。在响应体结束后异步写入，失败只记 warn。保留天数 `airopscat.request-log.retention-days`（默认 15，最小 1），由定时任务清理
- **应用日志**：config 端点异常时 `log.error("Error generating subscription: {}", msg, e)`；nodes/rules 端点异常由 `GlobalExceptionMapper` 记录 `Unexpected server error`
- 订阅生成过程**不写库**（没有访问计数、没有最后拉取时间）

---

## 8. 待决策 / 未确认清单

| 项 | 状态 | 建议 |
|----|------|------|
| 节点顺序（HashSet） | 无法复现 | 改为 `node.id` 升序，比对时忽略节点顺序 |
| `Content-Type` 是否带 `;charset=UTF-8` | 未确认 | 对生产执行 `curl -sD- -o/dev/null <url>` 记录完整响应头后对齐 |
| gzip 的触发条件 | 未确认 | 同上，加 `-H 'Accept-Encoding: gzip'` |
| 末尾 `/`、HEAD、`%2F` 的路由行为 | 未确认 | 接受末尾 `/`；规则端点拒绝路径穿越 |
| `toDate` 为 null 时订阅 400 | 已确认是现有行为 | 是否顺手修复需要单独决定（修复会改变对外行为） |
| 过期账户提示"已被禁用" | 已确认 | 保持一致 |
| 模板同步方式（profile → 服务器 `./config/subscription`） | 未确认 | 迁移时把 profile 目录作为唯一来源 |
| 列表索引越界 `short_id[0]` 为空数组 | 未确认 | 推测抛异常 → 400 系统内部错误 |

## 9. Go 版 ShadowTLS 输出约束

Java 与旧 profile 模板对 ShadowTLS 的裸出站输出不完整，不能作为金样保留：服务端已改为 ShadowTLS v3 + Shadowsocks 双层组合（见 `spec-deploy.md` 第 8 节）。订阅实现时 sing-box 应生成 SS 出站及 ShadowTLS 辅助出站，SS 的 `detour` 指向后者，策略组只引用 SS；外层使用节点握手密码，内层使用账户 UUID、`chacha20-ietf-poly1305`。Mihomo 等支持插件的客户端使用其组合格式；未核实支持的客户端不输出该节点。只按已验证的 TCP 能力输出，不声明普通 UDP 支持。
