# AirOpsCat 重构范围：模块保留/删减清单

> 状态：定稿（2026-10-19），决策见第 9 节；后续变更追加到决策记录。结论依据目录结构、类名、行数和少量类注释，实现时以现有代码行为为准。

## 1. 背景与目标技术栈

迁移原因：

1. 代码臃肿，需要重新梳理需求、简化系统。
2. 原生镜像打包耗时 5～7 分钟。
3. 应用常驻内存超过 100M，再加 MySQL，小型 VPS 难以承载。

目标技术栈（待原型验证）：Go 单二进制 + SQLite（WAL，`modernc.org/sqlite`）+ `html/template` 服务端渲染 + Tabler；sing-box 配置复用现有 inbound JSON 模板生成（`option` 包只在测试中用于校验），统计接口使用 `grpc-go`，SSH 使用 `golang.org/x/crypto/ssh`。

现状规模：Java 文件 330 个、约 3.2 万行；Controller 29 个、定时任务 19 个、实体 25 个、模板 111 个。

## 2. 判定标记

| 标记 | 含义 |
|------|------|
| 保留 | 核心链路，按原需求重写 |
| 简化 | 保留能力，去掉分支、配置项或冗余实现 |
| 合并 | 多个模块合成一个 |
| 待定 | 需要先确认实际使用情况 |
| 删除 | 不再实现，或由平台/外部工具替代 |
| 消失 | 框架带来的负担，Go 重写后自然不存在 |

## 3. 核心链路（第一阶段必须完成）

| 模块 | 现有实现（行数） | 建议 | 说明 |
|------|------------------|------|------|
| 用户与登录 | `UserService` 198、`AuthController`、`LoginController`、`LoginLockService` 70、`security/*`（RememberMe 等） | 简化 | 保留会话登录与失败锁定；RememberMe 改为长有效期会话 Cookie。目前只有单管理员，但保留 `user` 表与角色字段，路由按 admin/用户分组，便于以后扩展多管理员和用户自助 |
| 账户 | `AccountService` 526、`AccountController` 421、`AccountOnboardingService` 172、`MyAccountController` | 简化 | 限速字段 `speed`/`bandwidth`/`downloadMbps`/`uploadMbps` 都在使用，全部保留；`nodeMultiple`/`nodePrefix` 保留，`level` 删除。用户自助页（`MyAccountController`、`person/user-panel`）第一阶段不实现，只保留接口分组 |
| 节点与部署位 | `NodeService` 882、`NodeController` 526、`NodeServer`、`NodePlacementSyncService` 164 | 简化 | 保留"逻辑节点 → 部署位"模型；保留"按节点地区 + 类型自动同步部署位"规则 |
| 服务器 | `ServerService` 463、`ServerController` 445、`ServerHostService` 246 | 合并 | 删除 `ServerHost`，只用 `server.host` 存主连接地址。依据：代码中仅用 `resolvePrimaryHost` 取主 host（且已回退到 `server.host`）；部署位的对外域名走 `NodeServer.accessHost` → DNS 记录，服务器的域名列表来自 `dns_record.server_id`；非主 host 只作为 `legacyHosts` 展示。迁移时主 host 写入 `server.host`，未绑定 DNS 的遗留 host 列清单人工确认 |
| SSH | `service/ssh/*` 243、`config/ssh` | 保留 | 改用 `x/crypto/ssh`，本地端口转发能力随统计采集方式决定 |
| sing-box 配置与部署 | `singbox/*` 1264（`SingBoxConfigBuilder` 583）、`service/deployment/*`（版本 379、加载 246、执行 219、编排 187）、`CoreManagementService` 152 | 简化 | 复用现有 inbound JSON 模板生成配置，`option` 包只在测试中校验；部署只保留最近一次（配置哈希、时间、结果），删除多版本与部署历史，`NodeDeploymentVersionService` 和部署历史清理任务随之删除 |
| 订阅 | `SubscriptionService` 412、`SubscriptionController`（`/subscribe/config`、`/rules`、`/nodes`） | 保留 | 对外契约，URL 与输出格式必须兼容；新旧输出逐字节对比 |
| 路由规则 | `RouteRuleService` 322、`RouteRuleController` | 保留 | — |
| 流量采集 | `TrafficStatsTask` 236、`SingBoxGrpcQueryClient`、`SingBoxTrafficStatsCollector`、`AccountTrafficStatsService` 351 | 保留 | 改为批量写入 SQLite |
| 账户到期 | `AccountExpirationTask` 90 | 保留 | — |
| 系统配置 | `SystemConfigService` 520、`SystemConfigController` | 简化 | 部署级参数改用配置文件或环境变量，数据库只存运行期可调参数 |
| 订阅定制域名 | `AccountNodeSubscriptionDomainBindingService` 408、对应 Controller | 保留 | 定制用户必需；与流量阈值切换联动（切换时同步改定制域名） |
| 标签 | `TagService` 323、`TagController` 260、`account_tag`/`node_tag` | 保留 | 账户与节点通过共享标签关联，决定账户能用哪些节点；是订阅输出的前置条件，不能简化为文本字段 |
| 收支记账 | `TransactionService` 246、`TransactionController` 183 | 保留 | 在用；通过 `businessTable`/`businessId` 关联账户等业务记录，放第二阶段实现也可 |
| 文档授权接口 | `OpenController` `/api/open/docs-info/{authCode}` | 保留 | 被 `vpn-docs` 使用，属于跨项目契约 |

## 4. 运维增强（第二阶段）

| 模块 | 现有实现（行数） | 建议 | 说明 |
|------|------------------|------|------|
| 域名与 DNS | `DomainService` 191、`DomainDnsRecordService` 380、`DnsProviderConfigService` 275、`DomainDnsPullService` 200、`DomainDnsPushService` 170、`DomainDnsBindingService` 159、`ServerDomainService` 219、`CloudflareDnsProviderClient` 379、`DomainDnsScheduleTask` | 简化 | 只有 Cloudflare 一个实现，去掉 Provider 注册表抽象；拉取与推送都需要：以 Cloudflare 为准拉取，按绑定推送 A 记录（流量阈值切换依赖推送） |
| 流量阈值切换 | `ServerFailoverService` 408 | 保留 | 必要需求且实际触发过；达到流量阈值时把部署位域名和账户定制域名切到同节点其他部署位的服务器，依赖 DNS 推送能力 |
| 服务器监控 | `ServerMonitorTask` 128、`ServerMonitorStatsService` 365、`ServerTrafficStatsService` 178、`ServerVnstatStatsService` 181、`VnstatCollectTask` | 合并 | 三张服务器指标表（Monitor/Traffic/Vnstat）合成一张时间序列表 |
| 通知 | `BarkService` 369、`BarkController` 245、`service/expiration/*` 966（账户/域名/服务器到期、负载、流量阈值 5 个 Notifier）、`ResourceNotificationTask` | 合并 | 5 个 Notifier 合成"规则 + 一次扫描"的单一通知任务；删除开放的 Bark 测试接口 `/api/open/bark-test`，只保留后台内的测试发送 |
| 服务器维护脚本 | `ServerMaintenanceService` 248、`ServerMaintenanceController`、`ServerInstallController` | 简化 | 保留"选服务器执行 `profile/airopscat/shell` 脚本"；在线编辑脚本建议删除，脚本随仓库管理 |
| 数据库备份 | `DatabaseBackupService` 453、`BackupController` | 简化 | SQLite 用 `VACUUM INTO` 生成单文件快照，再按需上传，预计只需几十行 |
| 版本更新提醒 | `UpdateNotificationService` 167、`GitHubApiClient` | 删除 | 不再需要 |

## 5. 账户风控（重点简化对象）

已确认需求：在线 IP 或连接数超限时**告警并拒绝新连接**；需要跨节点聚合，用连接数限制在线设备数；流量超额后**限速**。据此收敛为两个模块：

- **在线与防共享**：周期采集各节点在线连接 → 按账户跨节点聚合 → 超过 `maxIps`/`maxConnections` 时告警并拒绝新连接。
- **超额限速**：流量超额后生成限速覆盖，写入 sing-box 配置并同步到服务器。

| 模块 | 现有实现（行数） | 建议 | 说明 |
|------|------------------|------|------|
| 在线 IP 采集 | `AccountOnlineIpService` 563、`SingBoxOnlineConnectionService`、`SingBoxClashApiClient`、`AccountOnlineIpCleanupTask` | 合并 | 与下面两项合成"在线与防共享"模块；`account_online_ip` 自带 `nodeId` 和 `lastOnlineTime`，同时承担"账户最近用过哪些节点"的查询 |
| 跨节点防共享 | `service/guard/*` 301、`AccountGuardEvaluationTask`、`AccountGuardEvictTask`、`/api/open/guard-sync` | 合并 | 保留跨节点聚合与拒绝新连接；评估与驱逐两个任务合成一次"采集 → 聚合 → 处置"流程 |
| 连接数超限告警 | `AccountOnlineLimitAlertService` 261 | 合并 | 作为处置步骤的告警输出，走统一通知模块 |
| 节点在线账户统计 | `NodeOnlineAccountStatsService` 506、`NodeOnlineAccountStatsTask`、`NodeOnlineAccountDailyStats`、两个统计页面 | 删除 | 统计页不再需要。保留的需求是"账户最近用了哪些节点"：在账户详情按 `account_online_ip` 分组展示节点及最后在线时间；该表保留期须覆盖"最近"的窗口（建议 30 天） |
| 超额处理 | `AccountTrafficOverQuotaService` 209、`AccountTrafficLimitService` 60、`RateLimitService` 373 | 合并 | 只保留"超额即限速"一种策略，合成超额限速模块 |
| 告警状态 | `AlertStateService` 80、`AlertStateController`、`AlertState` | 合并 | 并入通知模块，作为告警去重记录 |

## 6. 辅助功能

| 模块 | 现有实现（行数） | 建议 | 说明 |
|------|------------------|------|------|
| 系统日志 | `SystemLogService` 81、`SystemLogController` | 保留 | 统一记录部署、告警等事件 |
| 请求日志 | `SystemRequestLogService` 129、`SystemRequestLogFilter`、`SystemRequestLogProperties`、清理任务 | 删除 | 由反向代理访问日志替代 |
| 定时任务管理页 | `ProgrammaticTaskManager` 537、`system/scheduled-task` 页面 | 简化 | 任务周期固定在代码中，页面只展示上次运行时间和结果 |
| 控制台菜单注册 | `ConsolePageRegistry` 151 | 简化 | 菜单直接写在布局模板中 |
| 数据清理任务 | 账户流量、服务器监控、服务器流量、部署历史、系统日志、请求日志、在线 IP、内核配置等 8 个清理任务 | 合并 | 合成一个保留期任务，各表保留天数放在同一份配置里 |

## 7. 随重写消失的框架负担

| 内容 | 说明 |
|------|------|
| `JsonReflectionConfiguration` | 原生镜像反射注册，Go 不需要 |
| `NativeSshSmokeTestRunner`、`SshSmokeTestMode` | 原生镜像 SSH 冒烟测试 |
| `ObsoleteConfigTableMigration`、`ServerAuthColumnMigration` | 历史结构迁移，由一次性数据导入脚本取代 |
| `JacksonConfig`、`GzipRequestReaderInterceptor`、`BlockingTaskExecutorConfig`、`GlobalExceptionMapper` | 框架适配代码 |
| `model/convert`、`model/dto`、`model/vo` 分层 | 改为"handler + store"，按需定义请求/响应结构 |
| `CryptoConverter` | 能力保留（凭据加密存储），改成 store 层的一个函数 |

## 8. 数据表收敛预估

现有实体 25 个（另有关联表），按上述结论：

- 保留：`user`、`account`、`node`、`node_server`（部署位）、`server`、`route_rule`、`account_traffic_stats`、`domain`、`dns_record`、`dns_provider_config`、`system_config`、`system_log`、`account_node_subscription_domain_binding`、`tag`、`account_tag`、`node_tag`、`transaction`、`account_online_ip`（防共享聚合依赖）
- 合并：`server_monitor_stats` + `server_traffic_stats` + `server_vnstat_stats` → `server_metrics`；`node_deployment` 只保留每个部署位最近一次结果；`alert_state` 作为通知去重表保留
- 删除：`system_request_log`、`node_deployment_history`、`node_online_account_daily_stats`、`server_host`（并入 `server.host`）
- 删除字段：`account.level`

收敛后 21 张表（含 3 张关联表），比初稿预估多，主要因为定制域名、标签关联和记账都确认保留。

## 9. 决策记录

| # | 问题 | 结论 |
|---|------|------|
| 1 | IP 数/连接数超限的处置；是否跨节点聚合 | 告警并拒绝新连接；需要跨节点聚合，通过连接数限制在线设备 |
| 2 | 超额策略；限速字段使用情况 | 限速；`speed`/`bandwidth`/`downloadMbps`/`uploadMbps` 都在用（`speed` 经代码核对已无读写，见决策 24 D1） |
| 3 | 流量阈值自动切换 | 实际触发过，必要需求，保留自动切换 |
| 4 | 订阅定制域名绑定 | 必要需求，定制用户需要 |
| 5 | 记账、标签 | 都在用；标签用于关联账户与节点 |
| 6 | 部署版本与历史 | 只保留最近一次 |
| 7 | 多管理员/用户自助 | 目前没有，数据模型和路由保留扩展余地，第一阶段不实现 |
| 8 | 节点在线账户统计页 | 可以不看；但需要知道账户最近用了哪些节点，由 `account_online_ip` 提供 |
| 9 | 版本更新提醒 | 不用，删除 |
| 10 | `ServerHost` 并回 `Server` | 不影响功能即可合并；经代码核对可以合并（见第 3 节"服务器"） |
| 11 | DNS 拉取与推送 | 都需要 |
| 12 | `Account` 字段 | `nodeMultiple`/`nodePrefix` 在用，`level` 删除 |
| 13 | Bark 开放测试接口 | 不用，删除 |
| 14 | 部署位自动同步（`NodePlacementSyncService`） | 保留；后续调整为仅新建节点时按地区和类型创建初始部署位，之后手动增删（见 `spec-deploy.md` §1.2） |
| 15 | 开发分支 | 重写在新分支 `rewrite/go` 进行，Go 代码放仓库根目录；第一步实现核心链路原型 |
| 16 | 管理页面 | 可以重新设计，不必按现有页面原样复刻 |
| 17 | 对外接口协议 | 不修改：订阅 `/subscribe/*`、`/api/open/docs-info/{authCode}`、`/api/open/guard-sync` 等对外接口的路径、参数、响应头与响应格式保持兼容 |
| 18 | 订阅节点顺序 | 改为按 `node.id` 升序。现有顺序来自 `HashSet` 迭代，无法复现；客户端按名称引用节点，不属于协议变更。新旧输出比对时忽略节点顺序和 `# Generated at:` 行 |
| 19 | sing-box 配置生成方式 | 运行时不用 fork 的 `option` 包（节点 inbound 是自由格式 JSON，解析成结构体可能丢字段）；复用现有 inbound JSON 模板，`option` 只在测试中校验生成结果；统计 proto 重新生成而非 import |
| 20 | 部署现有问题（编号见 `spec-deploy.md` 第 7 节） | 修复：1、3、4、6、7、8、9、10、12；5 中禁用节点修复、外部服务器保持现状；2 保持现状（路由规则变化不置待部署）；11 不做配置（Clash API 地址端口固定写死，去掉可配置项） |
| 21 | 流量与风控现有问题（编号见 `spec-traffic.md` 第 8 节） | 1 改 `reset=false`，中心保存上次读数做差，读数变小视为内核重启、当前值即增量；2 服务器倍率上下行都乘；3 仅超额状态变化时推送；4 进入新周期或调整配额时重新判定恢复；5 到期检查改为每小时，无关联节点也禁用；6 保持单实例内存状态；7 保持只拒绝新连接，不断开存量连接；8 合并为一套连续计数，IP 超限也告警；9 切换与阈值提醒统一按 vnstat，阈值统一 0.85；10 任务周期固定在代码中用 ticker，不提供暂停；11 API Token 必填，systemd 改用 0600 的 `EnvironmentFile` |
| 22 | 订阅模板 | 改写为 Go `text/template`（`architecture.md` 第 8 节做法 A），用金样逐字节比对保证输出不变；`profile` 仓库同步修改，切换前 Java 与 Go 各用一套模板 |
| 23 | 验收环境 | 在现有 Java 生产服务器上验收，不另备测试服务器；验证期间停用 Java 服务，同一时刻只有一个版本运行 |
| 24 | 账户与辅助业务现有问题（编号见 `spec-account.md` 第 10、11 节） | 问题 1–24 全部按建议修复。D1 `account.speed`：导入前查生产库 `speed>0` 行数，没有就删列（修正决策 2 中"speed 在用"的说法）；D2 账户或标签影响节点用户集合的变化（启停、到期、续期、`uuid`、标签、删除）自动把相关部署位置为待部署，部署仍由管理员触发；D3 删除账户时订阅定制绑定和告警状态一起删除，收支流水保留；D4 `account_online_ip` 保留 30 天，按账户和节点分组查询"最近使用节点"；D5 删除标签 `disabled` 字段；D6 禁用用户不连带停用其账户；D7 记账按建议校验，系统日志先记 `SERVER_FAILOVER`、`DEPLOY`、`ALERT`；D8 删除 `airopscat.account.multiplier` |
| 25 | 导入时的数据问题（开发库发现） | `account.speed` 有数据（多为前端默认值 2048），确认删除，导入工具需显式加 `--drop-speed` 才执行；`auth_code` 重复的账户，保留未禁用且较早的一个，其余重新生成 `auth_code`，导入报告列出 |
| 26 | 列表分页 | 所有管理列表页都做服务端分页（每页 20 条）和查询参数筛选，不一次性返回全部数据 |
| 27 | 移动端 | 所有管理页面必须适配手机屏幕：表格横向滚动或折叠为卡片，表单单列排布，按钮触控区足够大；每个阶段验收时用手机宽度（约 390px）检查 |
| 28 | 服务器 JSON 配置 | 删除 `server.transit_config` 与 `server.core_config`：Java 版只存取、配置生成从未使用（开发库仅 3 行有值，其中 1 行是 `{}`）；页面和导入都不再保留 |
| 29 | 列表页 | 不显示"共 N 条"统计，只保留分页器 |
| 30 | 服务器域名 | 删除 `server.host`（决策 10 改为：`server_host` 和 `server.host` 都不保留）。SSH 连接一直用 `ip`；服务器的域名由绑定到它的 DNS 记录（`domain_dns_record.server_id`）体现；维护脚本的 `server_host` 变量改取绑定的第一条 DNS 记录，没有则用 IP（第 3 步落实）。导入时未绑定 DNS 的旧域名列入报告 |
| 31 | 节点字段与部署位 | 删除 `node.level`（订阅链路从未使用）和 `node.rule`（生成配置从未读取，开发库全部为 `{}`）；保存节点时入站配置必须是 JSON 对象且不含 `tag/listen/listen_port`；出站必须是落地节点；部署位访问域名只能选已绑定到该服务器的 DNS 记录（新建/绑定 DNS 记录随第二阶段的 DNS 模块）；"生成默认入站配置"随第 3 步的配置生成实现 |
| 32 | 移动端列表样式 | 列表行精简为「名称 + 一行摘要 + 状态徽标」，操作收进"更多"下拉菜单，筛选条件折叠在筛选按钮后；标签、出站等次要信息在手机上隐藏，桌面端保持完整列和内联操作按钮 |

## 10. 文档索引与下一步

| 文档 | 内容 |
|------|------|
| `architecture.md` | Go 版目录结构、数据库约定、HTTP、认证、定时任务、构建 |
| `spec-data.md` | 现有表结构、加密、认证、系统配置 |
| `spec-account.md` | 账户、用户、标签、记账、订阅定制域名的业务规则 |
| `spec-subscription.md` | 订阅与 docs-info 对外接口 |
| `spec-deploy.md` | 部署位同步、配置生成、SSH 部署 |
| `spec-traffic.md` | 流量采集、超额限速、防共享、流量阈值切换、账户到期 |
| `migration.md` | MySQL → SQLite 导入 |
| `acceptance.md` | 金样比对、验收指标、切换与回滚 |

第二阶段模块（DNS、通知、服务器监控、备份、维护脚本页面）的规格在进入第二阶段时再写。

第一阶段按四步推进，每步完成后汇报：

1. 骨架：项目结构、SQLite schema、登录、导入工具。
2. 账户、节点、部署位、服务器的增删改查，以及部署位自动同步。
3. sing-box 配置生成与 SSH 部署。
4. 订阅输出与流量采集，最后在目标 VPS 上实测内存和构建时间。
