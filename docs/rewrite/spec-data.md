# AirOpsCat 数据模型规格（Go + SQLite 重写用）

> 依据：`src/main/java/com/fun90/airopscat/model/entity/*.java`、`src/main/resources/db/migration/*.sql`（含未提交的 `V2026101804__drop_obsolete_schema.sql`）、`application.properties`（简称 `app.props`）。
> 以下路径默认省略前缀 `src/main/java/com/fun90/airopscat/`，实体文件省略 `model/entity/`。
> 调研时间 2026-10-18，分支 `rewrite/go`，只读调研，没有连生产库核对。

## 0. 全局约定

| 项 | 结论 | 依据 |
|----|------|------|
| Schema 来源 | Hibernate `schema-management.strategy=update` 按实体自动建表加列（不删列），自定义 SQL 迁移由人工执行 | `app.props:20`；迁移注释如 `V2026101701:3`「手工执行一次」 |
| 方言 | MySQL（`MySQLDialect`），Quarkus 3.24.4 | `app.props:9,22`；`pom.xml:19` |
| 命名 | `CamelCaseToUnderscoresNamingStrategy`：字段 `nickName` → 列 `nick_name`；没写 `@Column(name)` 的字段都按这个规则 | `app.props:25` |
| 主键 | 全部 `BIGINT AUTO_INCREMENT`（`GenerationType.IDENTITY`） | 各实体 `@Id` |
| 时间 | `LocalDateTime`/`LocalDate` 不带时区；JVM 默认时区设为 `Asia/Shanghai`，JDBC `serverTimezone=Asia/Shanghai`，所以库里存的是**上海本地时间** | `AirOpsCatApplication.java:15`；`app.props:12` |
| `create_time`/`update_time` | 大多数表由 `@PrePersist`/`@PreUpdate` 写入 `LocalDateTime.now()`，库里没有默认值 | 各实体 `onCreate/onUpdate` |
| 布尔标志 | 大多用 `Integer` 0/1（`disabled`、`enabled`、`exposed` 等），只有 `domain_dns_record.proxied`/`schedule_enabled` 是 `Boolean`（MySQL `bit(1)`） | 见各表 |
| 字符集 | 迁移建表统一 `utf8mb4` + `utf8mb4_unicode_ci`，**比较不区分大小写**（影响唯一键与等值查询，见第 6.5 节） | `V2026051601:17`、`V2026101701:22` |

**列类型说明**：「MySQL 类型」一列里，迁移 SQL 或 `columnDefinition`/`length` 明确写出的照原样列出；其余是按 Hibernate 6/7 + MySQLDialect 的默认映射**推断**的（标注「推」）：`String`→`varchar(255)`，`Integer/int`→`int`，`Long`→`bigint`，`Short`→`smallint`，`Byte`→`tinyint`，`Double`→`float(53)`（即 double），`Boolean`→`bit(1)`，`BigDecimal`→`decimal(38,2)`，`LocalDateTime`→`datetime(6)`，`LocalDate`→`date`，`@Lob String`→`longtext`。推断出来的类型都**没有连库确认**，迁移前建议对生产库跑一次 `SHOW CREATE TABLE`。

**外键说明**：Hibernate 会为 `@ManyToOne`/`@OneToOne`/`@JoinTable`/`@ElementCollection` 自动生成外键，名称随机（迁移脚本里专门按 `information_schema` 查找后删除，见 `V2026101702:9-40`）。下表「外键」列写的是实体映射推导出的外键，名称不固定。

---

## 1. 核心业务表

### 1.1 `user`（`User.java`）

`@UserDefinition`，供 Quarkus Security JPA 认证（`User.java:17`）。

| 列 | Java | MySQL | 可空 | 默认 | 含义 |
|----|------|-------|------|------|------|
| id | Long | bigint | 否 | 自增 | 主键 |
| email | String | varchar(255) 推 | 否 | | 登录名（`@Username`），**唯一** `User.java:23-25` |
| nick_name | String | varchar(255) 推 | 是 | | 昵称 |
| remark_name | String | varchar(255) 推 | 是 | | 备注名 |
| password | String | varchar(255) 推 | 否 | | bcrypt MCF 字符串（`@Password`），见第 3 节 |
| remark | String | varchar(255) 推 | 是 | | 备注 |
| role | String | varchar(255) 推 | 是 | | `@Roles`，单值：`ADMIN`/`PARTNER`/`VIP` |
| referrer | Long | bigint | 是 | | 推荐人 user.id（实体里没有关联映射，所以没有外键） |
| disabled | Integer | int | 是 | | 1=禁用 |
| failed_attempts | int（原始类型） | int | **否** 推 | Java 0 | 连续登录失败次数 |
| lock_time | LocalDateTime | datetime(6) 推 | 是 | | 锁定开始时间 |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | | |

唯一约束：`email`。

### 1.2 `account`（`Account.java`）

| 列 | Java | MySQL | 可空 | 默认 | 含义 |
|----|------|-------|------|------|------|
| id | Long | bigint | 否 | 自增 | |
| level | Integer | int | 是 | | 账户级别（非负，`AccountOnboardingService.java:77`），具体语义未确认 |
| node_multiple | Integer | int | 是 | | 节点倍数，取值 1–10（`AccountOnboardingService.java:74`） |
| node_prefix | String | varchar(255) 推 | 是 | | 订阅节点名前缀 |
| from_date / to_date | LocalDateTime | datetime(6) 推 | 是 | | 有效期；`to_date` 为空表示永不过期（`Account.java:103-108`） |
| period_type | String | varchar(255) 推 | **否** | | `PeriodType.name()`：`MONTHLY`/`YEARLY`（比较时忽略大小写，`util/TrafficPeriodUtils.java:23`） |
| uuid | String | varchar(255) 推 | 否 | | 代理协议 UUID |
| account_no | String | varchar(255) 推 | 否 | | 账号编号，**唯一** `Account.java:38-39` |
| auth_code | String | varchar(255) 推 | 否 | | 订阅授权码 |
| max_connections | Integer | int | 是 | | 最大连接数 |
| max_ips | Integer | INT（迁移） | 是 | | 跨节点去重 IP 上限，空或 ≤0 表示不限制（`V2026071101`） |
| speed | Integer | int | 是 | | 旧限速 KB/s，0/null 表示不限速（`Account.java:54-57`） |
| bandwidth | Integer | int | 是 | | 流量配额，单位 **GB**（`AccountService.java:311`） |
| download_mbps / upload_mbps | Integer | INT（迁移） | 是 | | 下行/上行限速 Mbps，空表示不限速（`V2026052301`） |
| disabled | Integer | int | 是 | Java 0 | 1=禁用 |
| remark | String | varchar(255) 推 | 是 | | |
| user_id | Long | bigint | 是 | | 所属用户，外键 → `user.id`（`Account.java:71-76`） |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | | |

唯一约束：`account_no`。外键：`user_id → user.id`。
**已删除的列（不要建）**：`max_online_ips`（`V2026101804:56`）。

### 1.3 `tag` 及关联表（`Tag.java`、`Account.java:78-84`、`Node.java:68-74`）

`tag`：

| 列 | Java | MySQL | 可空 | 默认 | 含义 |
|----|------|-------|------|------|------|
| id | Long | bigint | 否 | 自增 | |
| name | String | varchar(255) 推 | 否 | | **唯一** `Tag.java:20-21` |
| description | String | varchar(255) 推 | 是 | | |
| color | String | varchar(255) 推 | 是 | | 前端显示颜色 |
| disabled | Integer | int | 是 | Java 0 | 0=启用，1=禁用 |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | | |

`account_tag`（`account_id` BIGINT NOT NULL → `account.id`，`tag_id` BIGINT NOT NULL → `tag.id`）
`node_tag`（`node_id` BIGINT NOT NULL → `node.id`，`tag_id` BIGINT NOT NULL → `tag.id`）

- 两张都是 `@ManyToMany Set`，Hibernate 默认建复合主键 `(owner_id, tag_id)`（推断，未连库确认）。
- 代码里有原生 SQL 直接读写这两张表（`repository/TagRepository.java:27,36,144-182`）。
- 旧的 `node_server_tag` 表已删除（`V2026101804:65`）。

### 1.4 `node`（`Node.java`）—— 逻辑节点

| 列 | Java | MySQL | 可空 | 默认 | 含义 |
|----|------|-------|------|------|------|
| id | Long | bigint | 否 | 自增 | 入站 tag 为 `node_<id>`（`Node.java:101-103`） |
| port | Integer | int | 是 | | 入站端口 |
| protocol | String | varchar(255) 推 | 是 | | `ProtocolType.value`（小写，如 `vless`、`hysteria2`），比较时忽略大小写；类型校验见 `service/NodeService.java:362` |
| core_type | String | varchar(255) 推 | 是 | | 固定写 `sing-box`；启动时会把空值补上（第 5 节） |
| type | Integer | int | 是 | | `NodeType`：0=代理，1=落地 |
| inbound | String（原始 JSON） | json | 是 | | 入站配置 JSON |
| out_id | Long | bigint | 是 | | 出站（落地）节点 id，外键 → `node.id`（`@OneToOne`，`Node.java:64-66`） |
| rule | String（原始 JSON） | json | 是 | | 路由规则 JSON |
| level | Integer | int | 是 | | 节点级别（含义未确认） |
| disabled | Integer | int | 是 | | 1=禁用 |
| name | String | varchar(257) | 是 | | 名称，带 `-N` 编号后缀（`V2026101704`） |
| remark | String | varchar(255) 推 | 是 | | |
| region | String | varchar(64) | 是 | | 地区，用于按地区自动关联部署位（`Node.java:60-62`、`V2026101802`） |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | | |

索引：`idx_node_out_id(out_id)`、`idx_node_type_core_type(type, core_type)`。
**注意**：实体仍声明了 `idx_node_dns_record_id(dns_record_id)`（`Node.java:20`），但 `dns_record_id` 列已经由 `V2026101804:52` 删除，实体也不再映射它。这是遗留的不一致，Go 版不要建这个索引；Java 版在这种状态下能否正常启动**未确认**。
**`out_id` 唯一性未确认**：Hibernate 6+ 对拥有方 `@OneToOne` 的连接列可能会生成唯一约束，但多个代理节点共用同一落地节点在业务上是合理的，Go 版应只建普通索引。
**已删除的列**：`server_id`、`access_host_id`、`node_group`、`deployed`（`V2026101702:55-58`）、`no`（`V2026101703`）、`dns_record_id`、`failover_enabled`、`active_node_server_id`（`V2026101804:52-54`）。

### 1.5 `node_server`（`NodeServer.java`）—— 部署位

由 `V2026101701:7-23` 创建，后续迁移改过。

| 列 | Java | MySQL | 可空 | 默认 | 含义 |
|----|------|-------|------|------|------|
| id | Long | BIGINT | 否 | 自增 | |
| node_id | Long | BIGINT | 否 | | → `node.id`（实体里没有关联映射，所以没有外键） |
| server_id | Long | BIGINT | 否 | | → `server.id`，外键（`@ManyToOne EAGER`，`NodeServer.java:57-59`） |
| access_host_id | Long | BIGINT | 是 | | **现在指向 `domain_dns_record.id`**（`V2026101802` 之前指向 `server_host.id`），外键 → `domain_dns_record.id`（`NodeServer.java:61-63`）；订阅里的 host 优先取它 |
| exposed | Integer | INT | 是 | | 是否出现在订阅；null 或 1 视为暴露，0=只部署不订阅（`NodeServer.java:85-87`） |
| priority | Integer | INT | 是 | | 越小越优先 |
| deployed | Integer | INT | 是 | | `NodeDeploymentStatus`：0=待部署，1=已部署，2=待删除 |
| cheap_start / cheap_end | String | varchar(5) | 是 | | 便宜时段 `HH:mm`（上海时间，左闭右开，终点早于起点表示跨零点）`NodeServer.java:50-55` |
| create_time / update_time | LocalDateTime | DATETIME(6) | 是 | | |

唯一约束：`uk_node_server_node_server(node_id, server_id)`。索引：`idx_node_server_server_id(server_id)`、`idx_node_server_access_host_id(access_host_id)`（`NodeServer.java:17-24`）。
**已删除的列**：`legacy_node_id`（`V2026101702:61`）、`name`（`V2026101706`）、`enabled`（`V2026101804:55`）。

### 1.6 `server`（`Server.java`）

| 列 | Java | MySQL | 可空 | 默认 | 含义 |
|----|------|-------|------|------|------|
| id | Long | bigint | 否 | 自增 | |
| ip | String | varchar(255) 推 | 否 | | |
| ssh_port | Integer | int | 是 | | |
| auth_type | String | varchar(255) 推 | 是 | | `ServerAuthType.name()`：`PASSWORD`/`KEY`，空值按 `PASSWORD` 处理（`service/ssh/ServerSshConfigFactory.java:77-82`） |
| username | String | varchar(255) 推 | 是 | | SSH 用户 |
| auth | String | **TEXT** | 是 | | SSH 密码或私钥，**`CryptoConverter` 加密**（第 1.20 节）；`Server.java:41-43`、`V2026051701` |
| host | String | varchar(255) 推 | 是 | | 旧的接入主机名，启动时会用它回填 `server_host`（第 5 节） |
| region | String | varchar(64) | 是 | | 地区（`V2026101801`） |
| server_type | Integer | INT | 是 | | 0=代理服务器，1=落地服务器，取值同 `NodeType`（`Server.java:51-53`） |
| name | String | varchar(255) 推 | 是 | | |
| expire_date | LocalDate | date | 是 | | 到期日 |
| bandwidth_date | LocalDateTime | datetime(6) 推 | 是 | | 流量计费周期起点（`service/ServerVnstatStatsService.java:44`） |
| supplier | String | varchar(255) 推 | 是 | | 供应商 |
| price | BigDecimal | decimal(38,2) 推 | 是 | | 价格 |
| multiple | BigDecimal | decimal(38,2) 推 | 是 | | 流量倍率（含义推断） |
| bandwidth | Integer | int | 是 | | 流量配额，GB（推断，`controller/ServerController.java:264`） |
| cpu_cores | Integer | int | 是 | | |
| disabled | Integer | int | 是 | | 1=禁用 |
| external_server | Integer | int | 是 | | 是否外部服务器（Java 字段名 `external`）`Server.java:77-78` |
| remark | String | varchar(255) 推 | 是 | | |
| transit_config | String | json | 是 | | 中转配置 JSON |
| core_config | String | json | 是 | | 内核配置 JSON |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | | |

索引：`idx_server_expire_date(expire_date)`、`idx_server_supplier(supplier)`、`idx_server_state_expire(disabled, external_server, expire_date)`（`Server.java:16-23`）。

### 1.7 `route_rule` 及 `route_rule_server`（`RouteRule.java`）

| 列 | Java | MySQL | 可空 | 含义 |
|----|------|-------|------|------|
| id | Long | bigint | 否 | |
| name | String | varchar(255) 推 | 否 | |
| core_type | String | varchar(255) 推 | 否 | `sing-box` |
| rule_type | String | varchar(255) 推 | 否 | `RouteRuleType.value`：`domain`/`ip`/`source_ip`/`port`/`network`/`inbound`/`custom`（写入时归一化，`service/RouteRuleService.java:299-302`） |
| rule_value | String | json | 否 | 规则值 JSON |
| outbound_node_id | Long | bigint | 否 | 出站节点 `node.id`（没有关联映射，所以没有外键） |
| enabled | Integer | int | 是 | 1=启用 |
| remark | String | varchar(255) 推 | 是 | |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | |

`route_rule_server`：`route_rule_id` → `route_rule.id`，`server_id` → `server.id`（`@ManyToMany Set EAGER`，`RouteRule.java:43-49`），复合主键（推断）。

### 1.8 `domain`（`Domain.java`）

| 列 | Java | MySQL | 可空 | 含义 |
|----|------|-------|------|------|
| id | Long | bigint | 否 | |
| expire_date | LocalDate | date | 是 | |
| domain | String | varchar(255) 推 | 是 | 根域名（没有唯一约束） |
| price | BigDecimal | decimal(38,2) 推 | 是 | |
| supplier | String | varchar(255) 推 | 是 | |
| dns_provider_config_id | Long | bigint | 是 | → `dns_provider_config.id`（没有外键） |
| dns_provider_type | `DnsProviderType` | varchar 推，存字符串 | 是 | `CLOUDFLARE` |
| dns_sync_status | `DnsSyncStatus` | varchar 推，存字符串 | 是 | `NOT_SYNCED`/`SYNCED`/`PULLING`/`PUSHING`/`SYNC_FAILED` |
| dns_last_sync_time | LocalDateTime | datetime(6) 推 | 是 | |
| dns_binding_extension_json | String | json | 是 | 扩展信息（含 zone id 等，`service/DomainService.java:114`） |
| remark | String | varchar(255) 推 | 是 | |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | |

### 1.9 `domain_dns_record`（`DomainDnsRecord.java`）

| 列 | Java | MySQL | 可空 | 含义 |
|----|------|-------|------|------|
| id | Long | bigint | 否 | |
| domain_id | Long | bigint | 否 | → `domain.id`（没有外键） |
| server_id | Long | BIGINT | 是 | 绑定的服务器，同一时刻只绑一台（`V2026101801`、`DomainDnsRecord.java:27-29`） |
| dns_provider_config_id | Long | bigint | 是 | |
| external_record_id | String | varchar(255) 推 | 是 | 服务商侧的记录 id |
| name | String | varchar(255) 推 | 否 | 子域名 |
| full_name | String | varchar(255) 推 | 是 | 完整域名（迁移里按 `LOWER()` 做匹配） |
| type | String | varchar(255) 推 | 否 | `A`/`AAAA`/`CNAME` 等（迁移里用 `UPPER(type)='A'`，大小写可能混用） |
| content | String | text | 否 | 解析值 |
| ttl | Integer | int | 是 | |
| proxied | Boolean | bit(1) 推 | 是 | Cloudflare 代理开关 |
| priority | Integer | int | 是 | |
| status | `DnsRecordStatus` | varchar 推，存字符串 | 否 | `SYNCED`/`PENDING_CREATE`/`PENDING_UPDATE`/`PENDING_DELETE`/`SYNC_FAILED` |
| remark | String | varchar(255) 推 | 是 | |
| biz_tags_json / extension_json / raw_data | String | json | 是 | |
| last_sync_time | LocalDateTime | datetime(6) 推 | 是 | |
| schedule_enabled | Boolean | bit(1) 推 | 是 | 是否启用时段解析 |
| schedule_start / schedule_end | String | varchar(5) | 是 | `HH:mm`，上海时间，左闭右开，可跨零点 |
| schedule_content_in / schedule_content_out | String | varchar(255) 推 | 是 | 时段内/外的解析值（`DomainDnsRecord.java:102-104`） |
| failover_home_content | String | varchar(255) 推 | 是 | 自动切换切走时记下的归属服务器 IP（`DomainDnsRecord.java:93-95`） |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | |

没有声明唯一约束和索引。

### 1.10 `dns_provider_config`（`DnsProviderConfig.java`）

| 列 | Java | MySQL | 可空 | 含义 |
|----|------|-------|------|------|
| id | Long | bigint | 否 | |
| provider_type | `DnsProviderType` | varchar 推，字符串 | 否 | `CLOUDFLARE` |
| status | `DnsProviderConfigStatus` | varchar 推，字符串 | 否 | `ENABLED`/`DISABLED` |
| display_name | String | varchar(255) 推 | 否 | |
| credential_json | String | text | 是 | 凭据 JSON，**`CryptoConverter` 加密**（`DnsProviderConfig.java:36-38`） |
| extension_json | String | json | 是 | |
| last_check_time | LocalDateTime | datetime(6) 推 | 是 | |
| last_check_status | `DnsProviderCheckStatus` | varchar 推，字符串 | 是 | `NOT_CHECKED`/`SUCCESS`/`FAILED` |
| last_check_message | String | text | 是 | |
| remark | String | varchar(255) 推 | 是 | |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | |

### 1.11 `account_node_subscription_domain_binding` 及 `account_node_subscription_placement`

`AccountNodeSubscriptionDomainBinding.java`：账户订阅定制。

| 列 | Java | MySQL | 可空 | 默认 | 含义 |
|----|------|-------|------|------|------|
| id | Long | bigint | 否 | 自增 | |
| account_id | Long | bigint | 否 | | 外键 → `account.id` |
| node_id | Long | bigint | 否 | | 外键 → `node.id` |
| domain_dns_record_id | Long | BIGINT | **是** | | 外键 → `domain_dns_record.id`；为空表示不覆盖连接地址（`V2026101707:2`） |
| enabled | Integer | int | 是 | 1（`@PrePersist` 补齐） | |
| remark | String | varchar(255) 推 | 是 | | |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | | |

唯一约束：`uk_account_node_subscription_domain(account_id, node_id)`。索引：`idx_ansd_account_id`、`idx_ansd_node_id`、`idx_ansd_domain_dns_record_id`、`idx_ansd_enabled`（`AccountNodeSubscriptionDomainBinding.java:27-38`）。

`account_node_subscription_placement`（`@ElementCollection Set<Long> EAGER`，`AccountNodeSubscriptionDomainBinding.java:55-59`；建表见 `V2026101707:5-10`）：

| 列 | MySQL | 可空 | 含义 |
|----|-------|------|------|
| binding_id | BIGINT | 否 | → binding.id（外键由 Hibernate 生成，推断） |
| node_server_id | BIGINT | 否 | → `node_server.id`（没有外键） |

主键 `(binding_id, node_server_id)`，索引 `idx_ansp_node_server_id`。业务约束：每个 binding **只能选一个部署位**（`V2026101802:120-131`、`V2026101803:1`）；删除部署位或节点时用原生 SQL 清理这张表（`service/NodeService.java:795`、`service/NodePlacementSyncService.java:157`）。

### 1.12 `account_traffic_stats`（`AccountTrafficStats.java`）

| 列 | Java | 可空 | 默认 | 含义 |
|----|------|------|------|------|
| id | Long | 否 | 自增 | |
| user_id | Long | 否 | | 没有外键 |
| account_id | Long | 否 | | 没有外键 |
| period_start / period_end | LocalDateTime | 否 | | 统计周期 |
| bandwidth_quota | Long | 是 | | 配额，取自 `account.bandwidth`（GB）`service/AccountTrafficStatsService.java:289` |
| upload_bytes / download_bytes | Long | 是 | Java 0 | 字节 |
| create_time / update_time | LocalDateTime | 是 | | |

索引：`idx_account_traffic_account_period(account_id, period_start, period_end)`、`idx_account_traffic_user_period(user_id, period_start, period_end)`。

### 1.13 `account_online_ip`（`AccountOnlineIp.java`）

| 列 | Java | 可空 | 含义 |
|----|------|------|------|
| id | Long | 否 | |
| account_no | String | 否 | 账号编号（不是外键） |
| client_ip | String | 否 | 客户端 IP |
| connection_id | String | 是 | 连接 id |
| node_ip | String | 否 | 节点（服务器）IP |
| node_id | Long | 是 | 逻辑节点 id |
| node_tag | String | 是 | 入站 tag |
| last_online_time | LocalDateTime | 否 | |
| session_start_time | LocalDateTime | 是 | |
| create_time / update_time | LocalDateTime | 是 | |

唯一约束：`(account_no, node_ip, connection_id)`（名称由 Hibernate 生成，`AccountOnlineIp.java:13`）。注意 `connection_id` 可为空，MySQL 唯一键允许多个 NULL，SQLite 也一样。
索引：`idx_account_online_account_time(account_no, last_online_time)`、`idx_account_online_node_time(node_ip, last_online_time)`、`idx_account_online_logic_node_time(node_id, last_online_time)`、`idx_account_online_last_time(last_online_time)`。
写入用 MySQL `INSERT ... ON DUPLICATE KEY UPDATE`（`repository/AccountOnlineIpRepository.java:200-208`），删除用 `DELETE ... ORDER BY ... LIMIT`（同文件 `:177-178`）。

### 1.14 `node_deployment`（`NodeDeployment.java`）

| 列 | Java | MySQL | 可空 | 含义 |
|----|------|-------|------|------|
| id | Long | bigint | 否 | |
| node_id | Long | bigint | 否 | 逻辑节点 |
| node_server_id | Long | BIGINT | 是 | 部署位，**唯一**（`uk_node_deployment_node_server_id`，`V2026101701:225`；实体 `unique = true`） |
| version | Integer | int | 否 | 部署版本号 |
| snapshot_hash | String | varchar(64) | 否 | 快照哈希 |
| snapshot_json | String | json | 是 | 部署快照 |
| server_id | Long | bigint | 是 | |
| core_type | String | varchar(255) 推 | 是 | |
| protocol | String | varchar(255) 推 | 是 | |
| type | Integer | int | 是 | 0/1 同 `NodeType` |
| port | Integer | int | 是 | |
| disabled | Integer | int | 是 | |
| deployed_at | LocalDateTime | datetime(6) 推 | 是 | |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | |

索引：`idx_node_deployment_node_id(node_id)`。以前 `node_id` 上的唯一索引已删除（`V2026101701:31-46`）。**已删除的列**：`node_group`（`V2026101702:59`）。
（相关的 `node_deployment_history` 表不在本次范围，结构和这张表类似，含 `node_server_id`，见 `NodeDeploymentHistory.java`。）

### 1.15 `system_config`（`SystemConfig.java`）

| 列 | Java | MySQL | 可空 | 含义 |
|----|------|-------|------|------|
| id | Long | bigint | 否 | |
| config_key | String | varchar(128) | 否 | **唯一** |
| config_value | String | `@Lob`，longtext 推 | 是 | 值；部分 key 用 CryptoUtil 加密（第 4 节） |
| group_key | String | varchar(64) | 否 | 分组 |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | |

### 1.16 `system_log`（`SystemLog.java`）

| 列 | Java | MySQL | 可空 | 含义 |
|----|------|-------|------|------|
| id | Long | bigint | 否 | |
| category | String | varchar(32) | 否 | `SystemLogCategory.name()`，目前只有 `SERVER_FAILOVER`（旧值 `NODE_FAILOVER` 已由 `V2026101803:43-46` 改名） |
| level | String | varchar(8) | 否 | `INFO`/`WARN`/`ERROR`（`SystemLog.java:21-23`） |
| resource_id | Long | bigint | 是 | 关联资源 id（如节点） |
| resource_name | String | varchar(257) | 是 | |
| message | String | varchar(512) | 否 | |
| detail | String | text | 是 | 结构化 JSON 文本 |
| create_time | LocalDateTime | datetime(6) 推 | **否** | 只有创建时间，没有 `update_time` |

索引：`idx_system_log_category_time(category, create_time)`、`idx_system_log_resource(category, resource_id)`。

### 1.17 `transactions`（`Transaction.java`，注意表名是复数）

| 列 | Java | MySQL | 可空 | 含义 |
|----|------|-------|------|------|
| id | Long | bigint | 否 | |
| transaction_date | LocalDateTime | datetime(6) 推 | 否 | |
| amount | BigDecimal | decimal(38,2) 推 | 否 | 金额 |
| type | Integer | int | 否 | `TransactionType`：0=收入，1=支出 |
| business_table | String | varchar(255) 推 | 是 | `account`/`domain`/`server`（`controller/AccountController.java:331`、`DomainController.java:188`、`ServerController.java:383`） |
| business_id | Long | bigint | 是 | 对应业务表 id（没有外键） |
| description | String | varchar(255) 推 | 是 | |
| payment_method | String | varchar(255) 推 | 是 | `PaymentMethod.value`：`WeChat`/`AliPay`/`BankCard`（**区分大小写**比较，`PaymentMethod.java:26`） |
| remark | String | varchar(255) 推 | 是 | |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | |

### 1.18 `alert_state`（`AlertState.java`）

| 列 | Java | MySQL | 可空 | 含义 |
|----|------|-------|------|------|
| id | Long | bigint | 否 | |
| alert_type | String | varchar(255) 推 | 否 | `account-connection-limit`、`account-traffic-over-quota`、`account-expiring`、`server-expiring`、`domain-expiring`、`server-monitor-load`、`server-traffic-threshold`（各 Service 的 `ALERT_TYPE` 常量，如 `service/AccountOnlineLimitAlertService.java:24`、`service/expiration/*Notifier.java`） |
| resource_type | String | varchar(255) 推 | 否 | `account`/`server`/`domain` |
| resource_id | Long | bigint | 否 | |
| resource_key | String | varchar(255) 推 | 是 | |
| fingerprint | String | varchar(255) 推 | 否 | 去重指纹（如域名告警用域名本身，`DomainExpiringNotifier.java:64`） |
| status | String | varchar(255) 推 | 否 | `ACTIVE`/`ACKNOWLEDGED`/`RECOVERED`（`AccountOnlineLimitAlertService.java:26-28`、`AlertStateService.java:67`） |
| severity | String | varchar(255) 推 | 是 | `WARNING`/`INFO` |
| first_triggered_time / last_triggered_time / last_notified_time / recovered_time / acknowledged_time | LocalDateTime | datetime(6) 推 | 是 | |
| acknowledged_by | String | varchar(64) | 是 | |
| trigger_count | Integer | int | 是 | |
| current_value | Double | double 推 | 是 | Java 字段名是 `lastValue`（`AlertState.java:69-70`） |
| threshold_value | Double | double 推 | 是 | |
| summary | String | TEXT | 是 | |
| create_time / update_time | LocalDateTime | datetime(6) 推 | 是 | |

唯一约束：`uk_alert_state_identity(alert_type, resource_type, resource_id, fingerprint)`。索引：`idx_alert_state_type_status(alert_type, status)`、`idx_alert_state_resource(resource_type, resource_id)`。

### 1.19 服务器统计表

**`server_monitor_stats`**（`ServerMonitorStats.java`）：`id`；`server_id` Long 非空；`cpu_usage`、`memory_usage` Double；`memory_used_bytes`、`memory_total_bytes`、`network_rx_rate_bytes`、`network_tx_rate_bytes` Long；`sample_time` LocalDateTime 非空；`create_time`/`update_time`。索引 `idx_server_monitor_server_sample_time(server_id, sample_time)`。
**已删除的列**：`network_rx_bytes`、`network_tx_bytes`、`network_rx_increment_bytes`、`network_tx_increment_bytes`（`V2026052402`）。

**`server_traffic_stats`**（`ServerTrafficStats.java`）：`id`；`server_id` 非空；`period_start`/`period_end` 非空；`upload_bytes`、`download_bytes`、`monitor_upload_adjustment_bytes`、`monitor_download_adjustment_bytes` Long，Java 默认 0；`create_time`/`update_time`。索引 `idx_server_traffic_server_period(server_id, period_start, period_end)`、`idx_server_traffic_server_end(server_id, period_end)`。两个 adjustment 列的具体用途未确认（新建时置 0，`service/ServerTrafficStatsService.java:101`）。

**`server_vnstat_stats`**（`ServerVnstatStats.java`，建表 `V2026052401`）：时序快照，每次采集插入一行。

| 列 | MySQL | 可空 | 默认 |
|----|-------|------|------|
| id | BIGINT 自增 | 否 | |
| server_id | BIGINT | 否 | |
| iface | VARCHAR(32) | 否 | `'eth0'` |
| period_year | SMALLINT（Java Short） | 否 | |
| period_month | TINYINT（Java Byte） | 否 | |
| rx_bytes / tx_bytes | BIGINT | 否 | 0 |
| sampled_at | DATETIME | 否 | |
| create_time / update_time | DATETIME | 是 | 只有 `@PrePersist`，没有 `@PreUpdate` |

原来的唯一键 `uq_server_vnstat_period` 已删除（`V2026052403:4`）；索引 `idx_server_vnstat_sampled_at(server_id, sampled_at)`、`idx_server_vnstat_period_latest(server_id, period_year, period_month, sampled_at)`。

### 1.20 加密字段（`@Convert(CryptoConverter)`）

适用于 `server.auth`、`dns_provider_config.credential_json`；`system_config` 里 `storageEncrypted=true` 的 key 由 `SystemConfigService` 直接调用同一个 `CryptoUtil`（`service/SystemConfigService.java:166-178`）。

| 项 | 结论 | 依据 |
|----|------|------|
| 算法 | `AES/ECB/PKCS5Padding`，没有 IV | `util/CryptoUtil.java:23-24` |
| 密钥 | 配置项 `airopscat.crypto.secret-key`（默认 `AirOpsCatDefaultSecretKey2024`，生产可能用环境变量覆盖）。取 `key.getBytes()`（平台默认字符集，JDK 18+ 下是 UTF-8）**截断或右侧补 0x00 到 32 字节**，即 AES-256 | `CryptoUtil.java:27-28,50-56`；`app.props:118` |
| 密文格式 | 标准 Base64（带 padding，`Base64.getEncoder()`），明文按平台默认字符集（UTF-8）编码 | `CryptoUtil.java:73-74` |
| 空值 | null 或全空白字符串不加密，原样存储 | `CryptoUtil.java:64-66`；`config/CryptoConverter.java:27-29` |
| 防重复加密 | 写库前先尝试解密，解密成功且结果与原值不同则视为已加密、不再加密 | `CryptoConverter.java:33-35`；`CryptoUtil.java:111-123` |
| 读取兼容 | 解密失败（Base64 不合法或 padding 错）时**返回原文**，兼容历史明文 | `CryptoUtil.java:98-103` |

Go 实现要点：`aes.NewCipher(key32)` 按 16 字节分块做 ECB + PKCS#7 填充，`base64.StdEncoding`；解密失败时返回原文；写入前同样做「能解开就不再加密」的判断，避免二次加密。

---

## 2. 枚举取值与持久化方式

| 枚举（`model/enums/`） | 取值 | 持久化 | 使用位置 |
|----------------------|------|--------|----------|
| `CoreType` | `SING_BOX`(value=`sing-box`) | 字符串 value，比较时忽略大小写 | `node.core_type`、`route_rule.core_type`、`node_deployment.core_type` |
| `CoreOperation` | START/STOP/RESTART/RELOAD/STATUS/VALIDATE_CONFIG/INSTALL/UNINSTALL/UPDATE/CONFIG/GET_VERSION/GET_LOGS/IS_INSTALLED（code 为小写，如 `validate`、`is_installed`） | 不落库 | 运维操作 |
| `DnsProviderType` | `CLOUDFLARE` | `@Enumerated(STRING)` | `domain`、`dns_provider_config` |
| `DnsProviderConfigStatus` | `ENABLED`/`DISABLED` | STRING | `dns_provider_config.status` |
| `DnsProviderCheckStatus` | `NOT_CHECKED`/`SUCCESS`/`FAILED` | STRING | `dns_provider_config.last_check_status` |
| `DnsRecordStatus` | `SYNCED`/`PENDING_CREATE`/`PENDING_UPDATE`/`PENDING_DELETE`/`SYNC_FAILED` | STRING | `domain_dns_record.status` |
| `DnsSyncStatus` | `NOT_SYNCED`/`SYNCED`/`PULLING`/`PUSHING`/`SYNC_FAILED` | STRING | `domain.dns_sync_status` |
| `NodeBatchTagUpdateMode` | `REPLACE`(默认)/`APPEND` | 不落库（请求参数） | |
| `NodeDeploymentStatus` | 0=PENDING_DEPLOY，1=DEPLOYED，2=PENDING_DELETE；未知值或 null 按 0 处理 | int code | `node_server.deployed` |
| `NodeType` | 0=PROXY（代理），1=LANDING（落地） | int code | `node.type`、`server.server_type`、`node_deployment.type` |
| `PaymentMethod` | `WeChat`/`AliPay`/`BankCard` | 字符串 value（区分大小写） | `transactions.payment_method` |
| `PeriodType` | `MONTHLY`/`YEARLY` | 字符串 name | `account.period_type` |
| `ProtocolType` | `vless`(代理)/`vless-reality`(代理)/`hysteria2`(代理)/`shadowtls`(代理)/`shadowsocks`(落地)/`socks`(落地)；括号里是允许的节点 type | 字符串 value（比较时忽略大小写） | `node.protocol`、`node_deployment.protocol` |
| `RouteRuleType` | `domain`/`ip`/`source_ip`/`port`/`network`/`inbound`/`custom`（sing-box 字段分别对应 `domain`/`ip_cidr`/`source_ip_cidr`/`port`/`network`/`inbound`/无） | 字符串 value | `route_rule.rule_type` |
| `ServerAuthType` | `PASSWORD`/`KEY` | 字符串 name | `server.auth_type` |
| `SystemLogCategory` | `SERVER_FAILOVER`（服务器切换） | 字符串 name | `system_log.category` |
| `TransactionType` | 0=INCOME，1=EXPENSE | int code | `transactions.type` |

依据：各枚举文件全文，例如 `NodeDeploymentStatus.java:6-34`、`ProtocolType.java:10-15`、`RouteRuleType.java:7-13`。实体里只有 `Domain`、`DomainDnsRecord`、`DnsProviderConfig` 用了 `@Enumerated(EnumType.STRING)`，其他枚举都通过 String/Integer 字段手动转换；**没有任何字段用 ordinal 持久化**。

---

## 3. 用户认证

| 项 | 结论 | 依据 |
|----|------|------|
| 机制 | Quarkus 表单认证：`POST /j_security_check`，参数 `email`、`password`；登录页 `/login`，失败跳转 `/login?error=true`，成功跳转 `/dashboard`；session cookie 有效期 30 分钟、HttpOnly | `app.props:41-49` |
| 密码哈希 | `BcryptUtil.bcryptHash(pwd)`：bcrypt，**cost=10**，16 字节随机盐，编码为 Modular Crypt 格式（`$2?$10$` + 53 字符）。前缀具体是 `$2a$` 还是 `$2y$` **未确认**（Elytron 实现，Go 的 `golang.org/x/crypto/bcrypt` 这几种前缀都能校验）。新密码最少 6 位 | `config/DataInitializationConfig.java:39-41`；`service/UserService.java:132-138,177-179`；`quarkus-elytron-security-common-3.24.4` 的 `BcryptUtil` 字节码（`bipush 10`、`ModularCrypt.encodeAsString`） |
| 角色 | `user.role` 单值字符串：`ADMIN`、`PARTNER`、`VIP`（注册默认 `VIP`，`UserService.java:140`）。路径权限：`/api/admin/*` 仅 ADMIN；`/api/partner/*` 允许 ADMIN、PARTNER；`/api/vip/*` 允许 ADMIN、PARTNER、VIP；其余路径要求登录 | `app.props:63-81` |
| 公开路径 | `/static/*,/login,/api/logout,/register,/subscribe/*,/api/open/*,/favicon.ico,/error/*`；`/q/health/*,/q/metrics/*` | `app.props:55-60` |
| 禁用用户 | `disabled==1` 或用户不存在时返回匿名身份 | `security/UserStatusService.java:37-53` |
| 登录锁定 | 失败一次 `failed_attempts+1`；达到 **5 次**写入 `lock_time=now`；锁定 **30 分钟**（`now > lock_time + 30min` 才算解锁）。锁定期间登录抛出「仍处于锁定状态，请 N 分钟后再试」（N = 剩余分钟数 + 1）。认证成功时把 `failed_attempts` 重置为 0；`lock_time` **不会被清空**，只靠时间过期 | `service/LoginLockService.java:14-15,20-70`；`security/AuthenticationHandler.java:31-98`；`security/UserStatusService.java:56-60` |
| 锁定的副作用 | 锁定过期后 `failed_attempts` 仍然 ≥5，下一次失败会立刻再次锁定（按代码推断） | 同上 |
| 成功回调触发点 | `UserStatusAugmentor` 对每个非匿名身份都会执行状态检查并调用 `handleAuthenticationSuccess`，所以每个已登录请求都可能更新 `failed_attempts=0`（按 Quarkus 每请求执行 augmentor 推断） | `security/UserStatusAugmentor.java:33-55` |
| IP 限流 | `POST /j_security_check` 每个 IP 每 60 秒最多 15 次，超过返回 429；IP 优先取 `X-Forwarded-For` 第一个，其次 `X-Real-IP`，最后取远端地址；计数只在内存里 | `security/AuthenticationFailureObserver.java:25-26,117-163` |
| 失败提示 | 错误消息编码后通过 `&msg=` 拼到 `/login?error=true` | `AuthenticationFailureObserver.java:82-96`；`AuthenticationHandler.java:101-104` |
| 用户查找 | `findByEmail` 是 JPA 等值查询，在 MySQL `_ci` 排序规则下实际不区分大小写；注册查重显式用 `lower(email)` | `repository/UserRepository.java:16-22`；`UserService.java:128` |

### RememberMe

依据 `security/RememberMeService.java`、`RememberMeAuthenticationMechanism.java`、`UserStatusAugmentor.java:62-75`：

- 触发条件：`POST .../j_security_check` 且表单字段 `remember=on`，认证成功后下发 cookie（`RememberMeService.java:45-49`）。
- Cookie：名称 `remember-me`，`Path=/`，`HttpOnly`，`SameSite=Lax`；请求是 SSL 或 `X-Forwarded-Proto: https` 时加 `Secure`；`Max-Age = days*86400`，其中 `days` 来自 `airopscat.remember-me.days`，默认 14（`:52-61`、`app.props:51`）。
- 令牌格式：`base64url_nopad(email UTF-8) + "." + 过期时间epoch秒 + "." + base64url_nopad(HMAC-SHA256(key, payload))`，其中 `payload` 是前两段连同中间的点，`key = UTF8(airopscat.crypto.secret-key + "\0" + user.password)`（`user.password` 是 bcrypt 哈希字符串），所以改密码后旧令牌自动失效（`:92-106`）。
- 校验：拆成 3 段 → 解码 email（这里用的 `Base64.getUrlDecoder()` 兼容有无 padding）→ 已过期则拒绝 → 查用户 → 用常量时间比较签名（`:69-90`）。
- 恢复登录：表单 session 失效时，优先级 500 的机制读取这个 cookie，校验通过后用 `TrustedAuthenticationRequest(email)` 建立身份；用户被锁定或禁用时按未认证处理（`RememberMeAuthenticationMechanism.java:29-58`）。
- 令牌不落库，服务端没有吊销列表。

---

## 4. SystemConfig 已知 key

依据 `service/SystemConfigService.java:224-366`（`buildGroupDefinitions`）。行为规则：

- 启动时 `initializeDefaultConfigs` 为**库里没有的 key** 插入一行，值为代码里的 defaultValue（**不读** application.properties），然后**删除库里所有未在代码中定义的 key**（`:62-70,129-139,432-441`）。
- 读取：库里有这一行就用库里的值（加密的先解密）；没有才回退 `application.properties`/环境变量，再回退 defaultValue。结果缓存在内存，保存时同时更新缓存（`:143-164`）。加解密见 `:166-178`。
- 归一化：去掉首尾空白，空串存 NULL；checkbox 类型存 `"true"`/`"false"`，null 视为 `false`（`:443-453`）。数值读取解析失败时用调用方给的默认值（`:463-496`）。
- 下表「类型」取自 inputType：text/url/password 为字符串，number 为数值，checkbox 为布尔。标 🔒 的是 `storageEncrypted=true`，用 CryptoUtil 加密存储。分组顺序就是 group_key 的展示顺序。

### group `bark`（Bark 通知，`:226-240`）
| key | 类型 | 默认 | 含义 |
|-----|------|------|------|
| airopscat.bark.url | url，必填 | `""`（存 NULL） | Bark 服务地址 |
| airopscat.bark.device-key 🔒 | password，必填 | `""` | 设备 Key |
| airopscat.bark.encrypt-enabled | checkbox | false | 按 Bark 加密协议发送 |
| airopscat.bark.encrypt-key 🔒 | password | `""` | AES Key（32 字节） |
| airopscat.bark.encrypt-iv 🔒 | password | `""` | AES IV（16 字节） |
| airopscat.bark.default-group | text | AirOpsCat | 默认分组 |
| airopscat.bark.default-sound | text | healthnotification | 默认铃声 |
| airopscat.bark.default-icon | url | `""` | 默认图标 |

### group `open`（开放接口，`:242-256`）
| key | 类型 | 默认 | 含义 |
|-----|------|------|------|
| airopscat.subscription.url | url，必填 | http://localhost:8080/subscribe | 订阅地址 |
| airopscat.docs.url | url | https://docs.xxx.com | 文档入口 |
| airopscat.request-log.enabled | checkbox | true | 是否记录请求日志 |
| airopscat.request-log.paths | text，必填 | /api/open/docs-info/*,/subscribe/* | 记录哪些路径，支持 `/*` 前缀匹配 |
| airopscat.domain | text | yourdomain.com | 系统域名 |
| airopscat.api.token 🔒 | password | your_api_token_here | 开放接口/运维脚本 Token |
| airopscat.apple.id 🔒 | text | your_apple_id_here | Apple ID |
| airopscat.apple.pwd 🔒 | password | your_apple_pwd_here | Apple 密码 |

### group `account`（账号设置，`:258-268`）
| key | 类型 | 默认 | 含义 |
|-----|------|------|------|
| airopscat.account.multiplier | number | 1 | 统计数值展示倍率 |
| airopscat.account.traffic-over-quota.download-mbps | number | 1 | 流量超额后下行限速 Mbps |
| airopscat.account.traffic-over-quota.upload-mbps | number | 1 | 流量超额后上行限速 Mbps |
| airopscat.ratelimit.enabled | checkbox | false | 全局 sing-box 限速开关 |

### group `template`（模板与运维，`:270-280`）
| key | 类型 | 默认 | 含义 |
|-----|------|------|------|
| airopscat.config.templates.dir | text | ./config | 模板目录 |
| airopscat.install.scripts.dir | text | ./config/shell | 运维脚本本地目录 |
| airopscat.install.remote-work-dir | text | /tmp/airopscat-installer（placeholder 写的是 `/tmp/airopscat-maintenance`，两者不一致） | 远端工作目录 |
| airopscat.deployment.max-parallel-servers | number | 4 | 部署并发服务器数 |

### group `monitor`（监控与在线状态，`:282-319`）
| key | 默认 | 含义 |
|-----|------|------|
| airopscat.online.check-minutes | 10 | 在线检测时间窗口（分钟） |
| airopscat.account.online.cleanup.batch-size | 1000 | 在线记录清理批大小 |
| airopscat.account.guard.ttl-seconds | 30 | Guard 上报过期秒数（低于 5 按 5 处理；`V2026071401` 把旧值 15 改成了 30） |
| airopscat.account.online.history.retention-hours | 8 | 在线记录保留小时数 |
| airopscat.node.online-account.stats.sample-minutes | 5 | 节点在线趋势采样间隔（分钟） |
| airopscat.account.connection-limit.alert.enabled | true（checkbox） | 连接数超限告警 |
| airopscat.account.connection-limit.alert.consecutive-times | 2 | 连续超限多少次后加入黑名单 |
| airopscat.account.connection-limit.alert.min-interval-minutes | 60 | 告警最小间隔（分钟） |
| airopscat.account.connection-limit.alert.recovery-notify-enabled | false（checkbox） | 恢复时是否通知 |
| airopscat.sing-box.clash-api.host | 127.0.0.1 | Clash API 地址 |
| airopscat.sing-box.clash-api.port | 19191 | Clash API 端口 |
| airopscat.sing-box.clash-api.secret 🔒 | `""` | Clash API Bearer Token |
| airopscat.sing-box.clash-api.timeout-seconds | 5 | 超时秒数 |
| airopscat.sing-box.clash-api.max-retries | 1 | 重试次数 |
| airopscat.server.monitor.enabled | true（checkbox） | 启用服务器监控 |
| airopscat.server.monitor.refresh-minutes | 2 | 采集间隔（分钟） |
| airopscat.server.monitor.max-parallel-servers | 10 | 监控并发数 |
| airopscat.server.monitor.retention-days | 30 | 监控数据保留天数 |
| airopscat.server.monitor.cleanup.batch-size | 1000 | 清理批大小 |
| airopscat.server.monitor.cleanup.cron | `0 0 3 * * ?` | 监控清理 cron |
| airopscat.server.monitor.alert.cron | `0 */5 * * * ?` | 监控告警 cron |
| airopscat.server.traffic.notify.cron | `0 10 10 * * ?` | 流量阈值提醒 cron |
| airopscat.server.monitor.alert.cpu-threshold | 0.9 | 支持 0–1 或 0–100 两种写法 |
| airopscat.server.monitor.alert.memory-threshold | 0.95 | 同上 |
| airopscat.server.monitor.alert.traffic-threshold | 0.85 | 同上 |
| airopscat.server.monitor.alert.continuous-minutes | 30 | 超阈值持续多少分钟才告警 |
| airopscat.server.monitor.alert.min-interval-minutes | 60 | 告警最小间隔 |
| airopscat.server.vnstat.collect-minutes | 5 | vnstat 采集间隔 |
| airopscat.server.expiring.alert.min-interval-hours | 23 | 服务器到期提醒间隔 |
| airopscat.domain.expiring.alert.min-interval-hours | 23 | 域名到期提醒间隔 |
| airopscat.account.expiring.alert.min-interval-hours | 23 | 账号到期提醒间隔 |

（这一组除特别注明外都是 number 类型且必填；cron 是 text 类型。）

### group `history`（历史清理，`:321-338`，均为 number）
| key | 默认 |
|-----|------|
| airopscat.node.online-account.stats.retention-days | 90 |
| airopscat.node.online-account.stats.cleanup.batch-size | 1000 |
| airopscat.node.deployment.history.retention-days | 90 |
| airopscat.node.deployment.history.keep-latest-per-node | 20 |
| airopscat.node.deployment.history.cleanup.batch-size | 500 |
| airopscat.account.traffic.retention-days | 180 |
| airopscat.account.traffic.cleanup.batch-size | 1000 |
| airopscat.server.traffic.retention-days | 180 |
| airopscat.server.traffic.cleanup.batch-size | 1000 |
| airopscat.request-log.retention-days | 15 |
| airopscat.system-log.retention-days | 90 |

### group `backup`（数据库备份，`:340-351`）
| key | 默认 | 备注 |
|-----|------|------|
| airopscat.backup.dir | ./backup | |
| airopscat.backup.cron | `0 0 6 * * ?` | |
| airopscat.backup.cleanup.cron | `0 30 6 * * ?` | |
| airopscat.backup.retention-days | 30 | number |
| airopscat.backup.mysqldump-path | mysqldump | 换成 SQLite 后这一项失去意义 |

### group `scheduled`（定时任务，`:353-366`，均为 text 类型的 cron，Quartz 风格六段，时区 Asia/Shanghai，见 `scheduler/ProgrammaticTaskManager.java:29-30,164`）
| key | 默认 |
|-----|------|
| airopscat.account.expiration.cron | `0 0 5 * * ?` |
| airopscat.expiration.notify.cron | `0 0 10 * * ?` |
| airopscat.traffic.stats.cron | `0 */15 * * * ?` |
| airopscat.node.online-account.stats.cleanup.cron | `0 10 3 * * ?` |
| airopscat.node.deployment.history.cleanup.cron | `0 20 3 * * ?` |
| airopscat.account.traffic.cleanup.cron | `0 40 3 * * ?` |
| airopscat.server.traffic.cleanup.cron | `0 0 4 * * ?` |
| airopscat.request-log.cleanup.cron | `0 30 3 * * ?` |
| airopscat.system-log.cleanup.cron | `0 50 3 * * ?` |
| airopscat.core.config.cleanup.cron | `0 0 8 * * ?` |

`config/AppConstants.java` 里只有更新检查用的常量（`UPDATE_CHECK_TIMEOUT_SECONDS=10`、`UPDATE_CHECK_ENABLED=true`），和 system_config 无关。`docs/how-to-add-system-config.md` 记录的是新增配置的流程，没有额外的 key。
不在 system_config 里、只放在 `application.properties` 的安全相关配置：`airopscat.crypto.secret-key`、`airopscat.remember-me.days`。

---

## 5. 启动初始化（`config/DataInitializationConfig.java`）

监听 `StartupEvent`，在一个事务里执行（`:43-96`）；如果开启了 SSH 冒烟测试模式（`SshSmokeTestMode.isEnabled()`）则全部跳过（`:45-48`）。按顺序：

1. **`initializeSystemConfigs`**：补齐缺少的默认配置，并删除未定义的配置 key（第 4 节，`:112-114`）。
2. **`initializeNodeCoreType`**：`UPDATE node SET core_type='sing-box' WHERE core_type IS NULL OR trim(core_type)=''`（`:98-103`；`repository/NodeRepository.java:48-50`）。
3. **`initializeServerHosts`**：对每个 `server.host` 非空、且还没有主 host（`is_primary` 那一行）的服务器，插入一行 `server_host(server_id, host=trim(server.host), is_primary=1, enabled=1, sort=0)`（`:105-110`；`service/ServerHostService.java:112-132`）。`server_host` 表仍然映射在 `ServerHost.java`（列：`id, server_id NOT NULL, host NOT NULL, is_primary, enabled, sort, domain_id, remark, create_time, update_time`），但自 `V2026101802` 起 `node_server.access_host_id` 不再引用它。
4. **默认用户**：如果没有任何 `role='ADMIN'` 的用户，创建三个账号（`:53-95`）：
   - `admin@airopscat.com` / `admin123` / ADMIN / 昵称「管理员」
   - `partner@airopscat.com` / `partner123` / PARTNER / 「合作伙伴」
   - `vip@airopscat.com` / `vip123` / VIP / 「VIP用户」
   - 三者 `disabled=0`，密码都用 bcrypt 哈希。

---

## 6. SQLite 迁移注意点

1. **金额 `BigDecimal`**（`transactions.amount`、`server.price/multiple`、`domain.price`）：SQLite 没有定点小数，`NUMERIC` 亲和性实际按 REAL 存储，会有精度损失。建议存 TEXT（十进制字符串），或存整数「分」并统一缩放（MySQL 推断为 `decimal(38,2)`，即两位小数）；Go 端用 `shopspring/decimal` 之类的库或整数运算，统计 SUM 时注意不要直接用浮点。
2. **时间**：现有数据是不带时区的上海本地时间（`AirOpsCatApplication.java:15`、JDBC `serverTimezone=Asia/Shanghai`）。SQLite 建议统一存 `'YYYY-MM-DD HH:MM:SS[.ffffff]'` 文本，并约定为 Asia/Shanghai（或在导入时统一转成 UTC，并在规格里写清楚）。Go 端 `time.Time` 需要显式用 `time.LoadLocation("Asia/Shanghai")` 解析，避免 driver 默认按 UTC 处理。`HH:mm` 时段列（`cheap_*`、`schedule_*`）和 cron 都按上海时间解释。`expire_date` 只有日期，与 `LocalDate.now()` 比较（`Server.java:107-112`）。
3. **JSON 列**：`node.inbound/rule`、`server.transit_config/core_config`、`route_rule.rule_value`、`domain.dns_binding_extension_json`、`domain_dns_record.biz_tags_json/extension_json/raw_data`、`dns_provider_config.extension_json`、`node_deployment.snapshot_json`、`node_online_account_daily_stats.seen_account_nos_json` 在 MySQL 中是 `json` 类型；Java 端按原始字符串读写（`RawJsonDeserializer`）。SQLite 用 TEXT，可以加 `CHECK(json_valid(col))`。MySQL 存 json 时会规范化格式（键重排、空白变化），导出数据与原始写入的文本可能不同，如有哈希比较要注意（例如 `node_deployment.snapshot_hash` 的计算基于什么文本**未确认**）。
4. **布尔**：`bit(1)` 列（`proxied`、`schedule_enabled`）导出时可能是 `b'1'`/`\x01`，导入 SQLite 时统一转成 0/1 INTEGER。
5. **大小写不敏感**：MySQL `utf8mb4_*_ci` 下，`user.email`、`tag.name`、`account.account_no`、`system_config.config_key` 的唯一约束和等值查询都不区分大小写（例如 `findByEmail` 实际不区分大小写）。SQLite 默认 BINARY 比较，应在这些列上加 `COLLATE NOCASE`（只对 ASCII 有效）或写入前统一小写。迁移和代码里大量使用 `lower(...) like`（`RouteRuleService`、`DomainDnsRecordService`、`TagRepository.java:127-129` 等）以及 `LOWER(full_name)`/`UPPER(type)` 比较，SQLite 的 `LIKE` 默认对 ASCII 不区分大小写，行为基本兼容。中文排序和比较规则也会和 MySQL 不同。
6. **MySQL 专有 SQL 需要改写**：`INSERT ... ON DUPLICATE KEY UPDATE` → `INSERT ... ON CONFLICT(account_no,node_ip,connection_id) DO UPDATE`（注意 SQLite 中 `connection_id` 为 NULL 时不会触发冲突，MySQL 也一样）；`DELETE ... ORDER BY ... LIMIT` 需要 SQLite 编译开启 `SQLITE_ENABLE_UPDATE_DELETE_LIMIT`，或改成 `WHERE id IN (SELECT ... LIMIT n)`；其余原生 SQL 见 `TagRepository`、`NodeOnlineAccountStatsService.java:429,455`，改写前需逐条核对。数据库备份逻辑依赖 `mysqldump`，需要改成 SQLite 的 `VACUUM INTO` 或在线备份 API。
7. **自增与外键**：用 `INTEGER PRIMARY KEY AUTOINCREMENT`（或不加 AUTOINCREMENT 的 rowid）保留原有 id，导入时显式写入 id。SQLite 外键需要 `PRAGMA foreign_keys=ON` 才生效；现有库里有不少没有外键的逻辑引用（如 `node_server.node_id`、`route_rule.outbound_node_id`、`*_stats.server_id`），代码里靠手动删除来清理（例如 `NodeService.java:795-798`），Go 版要么照搬这些手动清理，要么补上外键并加 `ON DELETE CASCADE`，选定一种。
8. **不导入的表**：`node_failover_log`、`node_server_tag`、`server_config`、`migration_bak_*`、`migration_tmp_*`、`node_id_map` 都已经删除或是临时表（`V2026061402`、`V2026101804:61-66`）。导出前确认生产库已经执行到 `V2026101804`，否则需要手工剔除已删除的列。
9. **长度约束**：SQLite 不强制 `varchar(n)`，需要在 Go 校验层补上 `config_key ≤128`、`group_key ≤64`、`system_log.message ≤512`、`category ≤32`、`level ≤8`、`name ≤257`、`region ≤64`、`HH:mm` 列 ≤5、`snapshot_hash ≤64`、`acknowledged_by ≤64`。
10. **默认值**：库里几乎没有列默认值（只有 `server_vnstat_stats.iface='eth0'`、`rx/tx_bytes=0`，以及 `node_online_account_daily_stats` 的计数列），Java 字段初始值（如 `account.disabled=0`、`tag.disabled=0`、`binding.enabled=1`、`*_bytes=0`）只在应用层生效。Go 版建议把这些写成 SQLite 列默认值，或者在构造函数里统一设置。

---

## 未确认事项汇总

- Hibernate 推断出的列类型和长度（标「推」的部分），以及 `account_tag`/`node_tag`/`route_rule_server` 的复合主键、`node.out_id` 是否带唯一约束，都需要对生产库跑 `SHOW CREATE TABLE` 核实。
- `Node.java:20` 仍声明了已删除列 `dns_record_id` 上的索引，Java 版在这种状态下能否启动未验证。
- bcrypt 前缀（`$2a$` 或 `$2y$`）。
- `account.level`、`node.level`、`server.multiple`、`server_traffic_stats.monitor_*_adjustment_bytes` 的业务语义。
- `node_deployment.snapshot_hash` 是基于什么文本计算的（影响 JSON 列的规范化处理）。
- 生产库实际执行到哪个迁移版本（特别是需要手工执行的 `V2026101701` 到 `V2026101804`）。
