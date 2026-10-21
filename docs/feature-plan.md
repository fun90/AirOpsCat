# Go 版功能补齐与验收规划

状态：实施中。代码与自动化验证按下方逐项标记；真实环境与持续运行未完成的项目保持待验收。

对照基准：历史分支 `rewrite/go`（`516ff9e`）的 [docs/rewrite/](rewrite/scope.md) 与当前正式分支 `release/go`（`d04bb34`）。基于文档和代码静态核对，未在本次核对中运行测试或操作真实服务器。

## 1. 范围与现状

账户、用户、标签、节点、部署位、服务器、路由、订阅定制、收支、系统配置、sing-box 配置生成与 SSH 部署、订阅与 docs-info、流量差分、guard、Cloudflare、监控、Bark、维护脚本、SQLite 备份和发布安装均已有实现。本轮补齐行为闭环、迁移保障及验收证据，不重做这些模块。

历史规划已原样复制到 [docs/rewrite/](rewrite/scope.md)：[范围与决策](rewrite/scope.md)、[架构](rewrite/architecture.md)、[数据](rewrite/spec-data.md)、[账户](rewrite/spec-account.md)、[订阅](rewrite/spec-subscription.md)、[部署](rewrite/spec-deploy.md)、[流量与风控](rewrite/spec-traffic.md)、[迁移](rewrite/migration.md)、[验收](rewrite/acceptance.md)。这些是历史快照，其中与现状不符之处以本文和当前文档为准。

当前运行与验收约定以 [迁移说明](migration.md)、[验收记录](acceptance.md)、[部署说明](../deploy/README.md) 为准。本文中的差异须明确处置后再修改实现，不能仅凭旧规划覆盖后续决策。

## 2. 第一优先级：限速同步与恢复闭环

状态：代码已实现（`limit-sync` 任务按服务器比对限速指纹，不一致才 reload，失败下轮重试；见 `internal/web/traffic_tasks.go`、`limit_sync_test.go`），真机验收与 race 全量记录待补。历史依据：[scope.md](rewrite/scope.md) 决策 21，[spec-traffic.md](rewrite/spec-traffic.md) 第 2、8 节。

原问题（已提交版本 `d04bb34`）：采集路径只 reload 当前采集服务器，跨服务器账户可能保留旧限速；reload 失败后不会再次触发；周期翻转和调整配额后的恢复可能漏检。

二次核对（未提交工作区）：`syncLimits` 遍历所有运行目标服务器并比对限速指纹，每分钟的 `limit-sync` 任务使周期翻转、配额调整后的恢复最迟约 1 分钟自动下发，上述问题基本被覆盖。剩余风险：

- 指纹基线只在内存，重启后每台服务器首轮各 reload 一次。
- `AIROPSCAT_TRAFFIC_SERVERS` 白名单为空时，`limit-sync` 与 `traffic-collect` 都不处理任何服务器。
- `Reload` 下发整份当前配置，会连带生效其他待部署变更。
- 到期账户被过滤导致指纹变化，约 1 分钟自动撤权，这是副作用而非设计，且无测试覆盖。

### 目标行为

- [x] 账户进入超额或恢复时，将变化应用到承载该账户的相关服务器。
- [x] 新周期开始、账户配额调整后，即使没有新增流量，也能重新判定并恢复限速。
- [x] 下发失败保留待同步状态，后续可重试；成功后确认实际应用状态。
- [x] 有效配置未变化时，不因持续超额反复 reload。
- [x] 沿用服务器互斥、部署校验和成功快照，保持部署与限速同步一致。

### 验收

- [x] 同一账户部署在两台模拟服务器，由其中一台产生超额流量，两台最终应用限速。
- [x] 无新增流量时跨入新周期，两台恢复原有限速。（`limit_sync_test.go` 模拟周期结束）
- [x] 提高配额后恢复，降低配额后进入限速。（同上，含 race 运行）
- [x] 一台下发失败，后续重试成功；成功服务器不发生无意义重复 reload。
- [x] 对共享同步状态运行相关 race 测试。

## 3. 第一优先级：迁移一致性与完整对账

状态：代码已实现（源库 `REPEATABLE READ` 只读快照、敏感字段解密对账、金额按类型汇总对账、auth_code 核验，失败不发布输出），尚未对真实 MySQL 副本演练。历史依据：[rewrite/migration.md](rewrite/migration.md) 第 1、5 节。

当前 [导入工具](../cmd/airopscat-import/main.go) 对目标 SQLite 使用整体事务，但源 MySQL 通过普通查询读取，未见覆盖整个导出的源端一致性事务。已具备行数、外键、完整性与部分账户关键字段核对，尚缺加密字段语义对账及流水金额汇总对账。

### 目标行为

- [x] 源数据预检、导出和对账使用同一份 `REPEATABLE READ` 一致性快照，避免源库写入造成跨表时间点不一致。
- [x] 对新库敏感字段解密后与源字段转换前的明文比较，报告只记录字段定位和结果。
- [x] 按流水类型核对金额汇总，使用整数分和精确十进制转换。
- [x] 按既定去重规则核验 `auth_code`：保留项不变，重生成项唯一且报告可定位，不要求源目标全部原值相等。
- [x] 保留当前全新输出、失败清理、行数、外键、完整性和账户字段对账。

### 验收

- [ ] 导出期间源库发生并发修改时，产物仍对应同一快照。
- [ ] 人为制造金额或敏感字段不一致，对账失败且不发布正式输出文件。（代码已实现，仅有失败清理与去重的单元测试，未对真实 MySQL 演练）
- [x] 覆盖授权码去重、精确金额转换、旧明文兼容和失败清理。（`cmd/airopscat-import/main_test.go`）
- [ ] 在数据库副本执行完整导入，人工核对报告；不在日志中输出凭据或订阅令牌。

## 4. 次优先级：兼容能力与待明确功能

| 事项 | 当前状态 | 后续工作与验收 |
|------|----------|----------------|
| 响应 gzip（[spec-subscription](rewrite/spec-subscription.md) 第 7 节） | [x] 应用层通过 `writeSubscription` 为成功订阅文本与 docs-info JSON 提供 gzip；错误响应保持原格式 | `TestSubscriptionCompression` 覆盖协商、q=0、通配符、非法权重、HEAD、解压内容；`TestPublicSubscriptionAndDocs` 验证真实路由；生产代理链抓包待验收 |
| 备份远端上传（[scope](rewrite/scope.md) 第 4 节） | [备份模块](../internal/dbbackup/backup.go)已有本地快照、下载、删除和保留期清理 | 旧范围仅提及按需上传，目标和协议未定；确认是否需要后再实施，不预先增加存储适配层 |

## 5. 规划差异与处置建议

本节不是已批准的新需求；决策依据见 [scope.md 第 9 节](rewrite/scope.md)；先明确是否接受当前行为，再更新对应规范或实现。

| 事项 | 历史规划 | 当前行为 | 建议处置 |
|------|----------|----------|----------|
| 首次流量读数 | 首次作为增量记账（[验收](rewrite/acceptance.md) 第 4 节） | 首次仅建立基线 | 沿用当前迁移说明，实测切换区间并确定差额核对方式，避免重复计费或漏计 |
| 旧服务器指标 | 第二阶段补历史指标合并导入（[迁移](rewrite/migration.md) 第 3.1 节） | 不导入历史，从新采集建立 | 默认沿用当前迁移说明；需要历史查询时再明确迁移范围 |
| 模板目录 | `AIROPSCAT_TEMPLATES_DIR`，导入时丢弃旧数据库配置（[架构](rewrite/architecture.md) 第 3 节、[迁移](rewrite/migration.md) 第 3.3 节） | 订阅读取 `airopscat.config.templates.dir` | 明确统一入口和优先级，避免安装配置与实际读取不一致 |
| 每日任务 | 支持上海时间每日定点（[架构](rewrite/architecture.md) 第 9 节） | 备份与清理使用启动后每 24 小时执行 | 无固定时刻需求时保留现实现，并更新约定 |
| 到期撤权 | 旧流量规格包含部署移除用户（[spec-traffic](rewrite/spec-traffic.md) 第 5 节）；后续决策强调管理员触发部署 | 每小时禁用账户并标记待部署 | 明确节点侧撤权时限；若要求自动生效，补自动下发与失败重试，不能把数据库禁用等同于节点已撤权 |

## 5.1 二次逐项核对发现的缺口

依据：[scope.md](rewrite/scope.md) 与五份 [spec-account](rewrite/spec-account.md)、[spec-data](rewrite/spec-data.md)、[spec-deploy](rewrite/spec-deploy.md)、[spec-subscription](rewrite/spec-subscription.md)、[spec-traffic](rewrite/spec-traffic.md) 对照 `release/go` 工作区（含未提交改动），只读核对，相关包 `go test` 通过但多为缓存结果，未跑 race，未抓包。标“未确认”的须补测试或实测后定论。

### 建议实施

| 来源 | 缺口 | 说明 |
|------|------|------|
| [scope](rewrite/scope.md) 决策 24 D7、[spec-account](rewrite/spec-account.md) §8 | ~~系统日志缺 `DEPLOY`、`ALERT` 写入；`SERVER_FAILOVER` 的 `detail` 为空；无时间范围筛选~~ 已补：部署（`deploy.apply`）、告警触发（`web.raise`，按节流只记通知点）、故障切换 detail、日志页 `from`/`to` 日期筛选，`TestLogsPaginationFilteringAndRedaction` 覆盖筛选 | 完成 |
| [spec-subscription](rewrite/spec-subscription.md) §3 | ~~同一订阅定制绑定含多个部署位时只保留最后一个~~ 已修复：`SubscriptionNodes` 按集合过滤，`TestSubscriptionBindingKeepsAllPlacements` 覆盖 | 完成 |
| [spec-subscription](rewrite/spec-subscription.md) §7 | [x] 响应 gzip 已实现 | 与第 4 节合并验收；不依赖反向代理提供压缩 |
| [spec-subscription](rewrite/spec-subscription.md) §2 | 配额 ≤0 的 500G 展示兜底沿用现有契约；Shadowrocket `tfc` 已改为整数 HALF_UP，两位小数且避免溢出 | [x] `TestUsedGiBHalfUp` 覆盖中点、进位及 MaxInt64 |
| [spec-account](rewrite/spec-account.md) §10 问题 23 | [x] 后端开户缺省 `toDate` 时以开始时间加一个月，与当前前端默认一致；编辑保留显式清空日期语义。账户备注已补后端必填 | `TestAccountHTTPFlow` 验证省略日期后的开户默认值；底层导入与存量永不过期账户不改写 |
| [spec-traffic](rewrite/spec-traffic.md) §1.3 | [x] 采集失败发送 Bark 成功/失败计数汇总；单台失败后仍执行限速与 DNS 检查，并聚合返回错误；无目标时提前返回 | `TestCollectTrafficFailureStillChecksLimitsAndDNS` 通过 race；通知仅发计数，不泄露 SSH 错误；真实 Bark 送达待验收 |

### 需先确认是否有意取舍

| 来源 | 事项 |
|------|------|
| [spec-account](rewrite/spec-account.md) 问题 9 | [x] 续期必须晚于当前和原到期时间；需缩短时走账户编辑，避免续期误收款。`TestRenewToggleResetAndDelete` 验证相同/缩短时间拒绝且无额外流水 |
| [spec-account](rewrite/spec-account.md) §5.5 | 批量节点标签调整未实现，scope 无处置记录 |
| [spec-account](rewrite/spec-account.md) 问题 19 | [x] 收支页显示当前筛选全部记录的收入、支出、净额（整数分汇总，不受分页影响），`TestTransactionSummaryUsesFullFilter` 覆盖组合筛选、空结果及负净额；[x] 通用流水已按决策 24 D7 校验金额 >0（`TestTransactionCRUDFiltersAndBusinessValidation` 覆盖零和负数，先失败再修复）。月度趋势未纳入本次最小实现 |
| [spec-account](rewrite/spec-account.md) §7 | 新建 A 记录 TTL 为 300（规格为 1）；绑定无独立启停入口 |
| [spec-account](rewrite/spec-account.md) 问题 15 | 用户解锁、自助改密未实现（自助部分按决策 7 不做） |
| [spec-deploy](rewrite/spec-deploy.md) §2.3 | 默认入站模板 SNI/伪装域名固定为 `www.apple.com`，hysteria2 不再取访问域名，reality 不再随机握手域名 |
| [spec-deploy](rewrite/spec-deploy.md) §4.2 | `04-certificates-acme.sh` 硬编码默认 ACME 邮箱；防火墙放宽至 8000–65535；节点端口无自动分配 |
| [spec-data](rewrite/spec-data.md) §3 | `system_config` 默认值与规格不同（流量保留 30 对 180 天，备份保留 15 对 30 天，模板目录默认空）；约 10 个规格键无对应项 |

### 小问题与待实测

- [x] `store/settings.go` 已移除失效请求日志、Clash 端口/重试和倍率配置的不可达校验；`ActiveSetting` 继续限制可保存键。SQL 层长度校验未扩展，沿用业务入口校验。
- 订阅：[x] 已补 `Referrer-Policy`，成功文本和 JSON 响应统一带 charset（保留旧 `charset` 头兼容）；`TestPublicSubscriptionAndDocs` 验证订阅响应头。`X-XSS-Protection` 为旧浏览器废弃机制，沿用现有安全头；`docs-info` 的 `nickName` 为空仍输出；快照 `disabled`/`type` 额外过滤可能误伤生产快照；HEAD、`%2F`、`?dns=` 空值无测试。
- 部署：`deploy_revision` 触发器覆盖流量表与配置表每次写入，采集频繁时部署期间“数据已变化”误判概率升高，影响面未确认。
- 账户列表 store 层支持 `online`、`userID` 筛选，页面未接出。
- 超额告警正文缺“已通过 sing-box 原生限速生效”一行（影响小）。

### 已确认落实（不再计入欠项）

表收敛与删列、仅 admin 路由、reset=false 做差、倍率上下行都乘、周期锚点独立存储、guard 聚合与 Token 必填、vnstat 口径阈值 0.85、固定 ticker、原子部署与服务器互斥、TOFU、ShadowTLS 链路、`v1:` AES-GCM、bcrypt 与登录锁定、模板金样及外部模板树一致。

## 6. 已实现能力的剩余验收

详细结果统一记录到 [验收记录](acceptance.md)，验收方法与指标见 [rewrite/acceptance.md](rewrite/acceptance.md)，不在多个文档重复维护执行结果。已有 [Qute 模板金样测试](../internal/subscribe/render_test.go)，以下生产数据及真实环境验收仍需补充完成证据。

| 验收组 | 待完成内容 | 完成标准 |
|--------|------------|----------|
| 对外契约 | 同一份源数据下订阅、规则、docs-info、guard 回放和服务器配置对照 | 覆盖模板、账户边界、状态码、响应头和响应体；只允许已确认差异，配置通过远端 check |
| 计费与限速 | 客户端实流量、服务器倍率、超额、恢复、周期切换与内核计数重置 | 客户端与服务端读数可核对，限速及恢复在相关服务器生效 |
| guard | agent 上报、跨节点聚合、超限和恢复 | 观察到内核拒绝新连接；保留存量连接行为符合约定 |
| 客户端与协议 | 各订阅格式导入、代理实流量、ShadowTLS | 真实客户端可导入并完成代理请求 |
| DNS 与通知 | 真实 Cloudflare 切走及回切、真实 Bark 送达及加密 | 远端结果、数据库和告警状态一致 |
| 维护初始化 | 新服务器执行 01～10，尤其替换公钥后重连 | 后续步骤可建立新 SSH 连接，服务正常，失败停止和清理有效 |
| 安装与回滚 | 隔离环境完整安装、升级、实际迁移失败恢复 | systemd、二进制与 DB/WAL/SHM 文件组恢复正确，原服务不受影响 |
| 发布 | GitHub 云端 CI、双架构 Release 与安装产物 | 云端检查通过，校验和及安装行为核验完成；arm64 实机运行另留记录 |
| 持续运行 | 目标主机真实数据运行至少 24 小时，正式切换观察至少 3 天 | RSS 满足 ≤50 MB 目标，计费、限速、guard、DNS、通知无未解释异常 |
| 正式切换 | 最终导入、生产域名切换、停止旧服务、回滚与差额核对 | 留存切换和回滚记录，不让两套管理服务同时部署或采集 |

真机验证仅使用既有约定的 `<测试主机>`，连接前核对目标身份。生产安装、DNS 变更、真实通知和正式切换依各自任务授权执行；本文落档不表示已授权这些操作。金样与真实数据放在仓库外，不提交凭据和令牌。

## 7. 推进顺序与完成标准

1. 补齐限速状态同步、周期恢复和失败重试，并通过相关自动化与 race 测试。
2. 补齐导入一致性快照及完整对账，在源库副本演练。
3. 明确第 5 节差异，完成生产数据契约对照。
4. 在指定测试服务器完成实流量、guard、限速、维护和安装回滚验收；外部服务变更单独安排。
5. 完成 24 小时持续运行验证后准备正式切换，切换后完成至少 3 天观察。

每项实施完成时记录改动、实际检查、结果和剩余限制。未执行或观察未结束的项目保持待验收。

不纳入本轮：用户自助页、多版本部署历史、节点在线日统计、版本更新提醒、请求日志存储，以及其他已明确删除的模块。
