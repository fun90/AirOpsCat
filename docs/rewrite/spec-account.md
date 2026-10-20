# Go 重写规格：账户、用户、标签、记账、订阅定制域名、系统日志

> 范围与决策见 `docs/rewrite/scope.md`（第 3、5、6、9 节）；表结构见 `docs/rewrite/spec-data.md`（本文不重复列定义，只引用 §1.1 user、§1.2 account、§1.3 tag、§1.11 binding、§1.12 account_traffic_stats、§1.13 account_online_ip、§1.16 system_log、§1.17 transactions）。
> 订阅输出见 `spec-subscription.md`，部署与下发见 `spec-deploy.md`，流量、超额、防共享、阈值切换、到期任务见 `spec-traffic.md`。本文只写**业务规则**，不写页面和接口形状。
> 引用格式 `文件:行号`，路径相对 `src/main/java/com/fun90/airopscat/`。标「未确认」的是推断或未连库核实的结论。没有写入任何真实凭据。

## 0. 结论速览

| 主题 | 结论 |
|------|------|
| 创建入口 | 管理页只走 `POST /api/admin/accounts/onboarding`（`resources/.../static/js/person/account.js:1142`）。旧的 `POST /api/admin/accounts` 仍在但**没有任何校验**（`controller/AccountController.java:153-184`），Go 版只需保留 onboarding 语义 |
| 自动生成 | `accountNo` = 随机 UUID 去掉连字符后取前 12 位小写十六进制；`authCode` = 随机 UUID 去掉连字符后 32 位小写十六进制；`uuid` = 标准带连字符小写 UUID（`service/AccountService.java:348-360,491-499`） |
| 自动副作用 | 开户**不**自动建标签、不自动部署；只有「填了金额」才记一笔收入流水；用户可同事务新建 |
| 下发 | 账户的任何修改都**不**标记待部署；仅 `accountNo`/`maxIps`/`downloadMbps`/`uploadMbps` 变化或账户新增/删除会触发限速全量同步（且全局限速开关默认关）。`uuid`、`disabled`、`toDate`、标签变更后须手动「部署」（§1.6） |
| 流量重置 | **没有**「重置流量」操作。周期由 `toDate` 的日/时刻推导，过期靠周期边界自然切换（§1.5） |
| 最近使用节点 | **现在没有**按账户的「最近用过哪些节点」查询；数据只能来自 `account_online_ip`，而它默认只保留 8 小时（§3） |
| 标签 | 账户与节点通过共享标签授权；**标签的 `disabled` 字段对授权、部署、订阅都无影响**（§5.4） |
| 系统日志 | 全库只有一个分类 `SERVER_FAILOVER`，只有服务器自动切换在写（§8）；决策文档说「统一记录部署、告警」，现状不是这样 |
| 与 scope 决策冲突 | **`account.speed` 实际是死字段**：没有写入路径也没有读取路径（问题 1），与决策 2「speed 都在使用」不符 |

---

## 1. 账户（Account）

### 1.1 字段取值与默认值

列定义见 spec-data §1.2。业务层的默认与约束：

| 字段 | 规则 | 依据 |
|------|------|------|
| `uuid` | 创建时为空则生成 `UUID.randomUUID().toString()`；更新时必填、必须是合法 UUID、规范化为小写带连字符、不能与其他账户重复，错误提示分别为「UUID不能为空」「UUID格式不合法」「UUID已存在」。**创建时不校验格式也不查重** | `AccountService.java:348-350,419-437` |
| `authCode` | 创建时为空则生成 32 位十六进制；可手工指定任意字符串；**无唯一约束、不查重**；`findByAuthCode` 取第一条 | `AccountService.java:353-355,492-494`；`repository/AccountRepository.java:43-45` |
| `accountNo` | 创建时为空则生成 12 位十六进制；列有唯一约束（MySQL 下不区分大小写）；创建与更新都不预检，冲突时抛 `PersistenceException`；它同时是 sing-box 用户名（`DeploymentDataLoader.java:238`）和 `account_online_ip.account_no` 的关联键 | `AccountService.java:358-360,498-499`；`model/entity/Account.java:38-39` |
| `periodType` | 为空默认 `MONTHLY`；不校验取值（非 `YEARLY` 的任何字符串都按月处理，见 `util/TrafficPeriodUtils.java:23-30`） | `AccountService.java:372-374` |
| `disabled` | 空默认 0 | `AccountService.java:368-370` |
| `fromDate`/`toDate` | 后端**不给默认值**：`toDate` 为空 = 永不过期。管理页前端默认 `fromDate=now`、`toDate=now+1 个月` | `Account.java:103-108`；`static/js/person/account-form-defaults.mjs`（`createAccountFormDefaults`） |
| `nodeMultiple` | onboarding 校验必填且在 1–10；更新路径不校验（前端要求非空） | `AccountOnboardingService.java:74-76`；`account.js:1093` |
| `nodePrefix` | 自由文本，可空；与 `nodeMultiple` 一起用于订阅节点名混淆（`SubscriptionService.java:119-120,176-177`） | |
| `level` | onboarding 校验非负；订阅/部署都不读取；**决策 12 要求删除** | `AccountOnboardingService.java:77`；spec-subscription §2.2 |
| `maxConnections`/`maxIps` | onboarding 校验 ≥0；`maxIps` 空或 ≤0 = 不限；`maxConnections` 空或 ≤0 = 不限（运行时判断见 spec-traffic §3） | `AccountOnboardingService.java:78-79`；`Account.java:47-52` |
| `bandwidth` | 单位 GB；onboarding 校验 ≥0；空或 ≤0 = 不限量 | `AccountOnboardingService.java:80`；`AccountService.java:310-315` |
| `downloadMbps`/`uploadMbps` | onboarding 校验若填写必须 >0；空 = 不限速 | `AccountOnboardingService.java:81-82` |
| `remark` | 自由文本。onboarding 后端**不要求**，前端要求必填；订阅 shadowrocket 地址用它做路径段（remark 为空会 NPE，`SubscriptionService.java:221-223`） | `account-form-defaults.mjs`（`validateAccountOnboardingForm`） |
| `userId` | 可空；非空则用户必须存在，否则 `EntityNotFoundException("User with ID .. not found")`（旧接口路径）。外键 → user | `AccountService.java:363-365` |
| `speed` | **死字段**，见 §1.9 与问题 1 | |

Bean Validation 注解：本批 DTO/实体**没有任何** `@NotNull`/`@Size`/`@Valid`（全仓只有 `SshConfig` 用到，grep 确认）。所有校验都是手写的，集中在 `AccountOnboardingService.validateRequest` 和各 Service 里。

### 1.2 创建（开户 / onboarding）

入口 `AccountOnboardingService.onboard`（`service/AccountOnboardingService.java:38-53`），整个方法在一个事务内，任何一步抛运行时异常即整体回滚（用户、账户、标签、流水都不保留）。步骤：

1. **请求校验**（`:55-96`），按顺序，第一个失败即返回 400：
   - 请求体为空 →「开户请求不能为空」。
   - `userId` 与 `newUser` **有且仅有一个** →「请选择且仅选择一种用户来源」。
   - `account` 为空 →「账户资料不能为空」。
   - `fromDate`、`toDate` 都有时要求 `toDate` 晚于 `fromDate` →「到期时间必须晚于开始时间」。
   - `nodeMultiple` 必填且 1–10 →「节点倍数必须在 1 到 10 之间」。
   - `level`/`maxConnections`/`maxIps`/`bandwidth` 若有值不得小于 0 →「X不能小于 0」。
   - `downloadMbps`/`uploadMbps` 若有值必须 >0 →「下行限速必须大于 0」等。
   - `amount` 若非空：必须 >0 →「请输入有效金额」；`paymentMethod` 必填 →「填写金额时必须选择付款方式」；值必须是 `WeChat`/`AliPay`/`BankCard` 之一（区分大小写）→「付款方式不合法」。
2. **标签校验**：`validateAccountTagIds` 对 tagIds 去重后查库，数量不符 → `EntityNotFoundException("部分标签不存在")`（404）。
3. **用户解析**（`:110-129`）：
   - 已有用户：不存在 → 404「用户不存在」。
   - 新用户：走 `UserService.saveUser`（§4.2）；邮箱重复 → 409「邮箱已存在」。新用户字段 `email/password/nickName/remarkName/remark/role/disabled` 全部透传（前端现在只发 `email/password/nickName/role/disabled`，`account-form-defaults.mjs:buildOnboardingUserSource`）。
4. **保存账户**（`AccountService.saveAccount`，`:345-379`）：补齐 `uuid`/`authCode`/`accountNo`/`disabled`/`periodType` 默认值，持久化，**事务提交后**异步触发限速全量同步（`RateLimitService.triggerAsyncSync`，`service/ratelimit/RateLimitService.java:284-305`；全局限速开关关闭时整轮跳过）。
5. **标签关联**：`tagIds` 非空才写 `account_tag`（`updateAccountTags`，先删后插）。**不会自动给账户打任何标签**，没有标签的账户订阅里没有任何节点。
6. **首期流水**（`:153-168`）：仅当 `amount != null` 生成一条 `type=0(INCOME)`、`businessTable="account"`、`businessId=新账户id`、`description="账号首期：N月"`、`remark=账户备注`、`paymentMethod`、`transactionDate=now` 的 Transaction。`N` 的算法见 §6.3。
7. **不触发部署**：没有调用部署服务，没有标记部署位待部署。新账户要出现在节点的 sing-box users 里，要等下一次该节点部署，或限速同步（开关开启时它会整机重建配置，顺带带上新账户，见 spec-deploy §3.6）。

错误映射（`AccountController.java:186-205`）：`UserEmailAlreadyExistsException`→409，`EntityNotFoundException`→404，`IllegalArgumentException`→400，`PersistenceException`→409「数据已存在或不符合唯一性约束」（账户编号冲突走这条）。响应为新用户、新账户、可空流水三段。

管理页默认值（前端工厂，**后端不强制**）：`level 4`、`nodeMultiple 2`、`nodePrefix "8"`、`maxConnections 100`、`maxIps 2`、`bandwidth 500`、`downloadMbps 12`、`uploadMbps 8`、`periodType MONTHLY`、`fromDate=now`、`toDate=now+1 个月`、新用户角色 `VIP`、密码为前端随机生成的 16 位字母数字（`account-form-defaults.mjs`）。其中 `maxIps/downloadMbps/uploadMbps` 与 openspec 写的 3/20/10 不一致，见 §1.10。

Go 版建议：保留 onboarding 一个创建入口（用户来源二选一 + 账户 + 可选首期金额），旧的无校验创建接口不要复刻；`accountNo`/`authCode`/`uuid` 生成规则保持（兼容现有格式），但对 `accountNo`、`authCode` 加唯一约束和预检，并把 `uuid` 格式校验提前到创建。

### 1.3 修改

入口 `AccountService.updateAccount`（`:381-417`）。管理页编辑弹窗提交全部字段（`account.js:1157-1166`）。

| 规则 | 说明 |
|------|------|
| UUID | 必填、合法、规范化、查重（§1.1） |
| 字段覆盖方式 | 用反射 `copyNonNullProperties`（`:505-524`）：**源字段为 null 就不覆盖**，所以 `toDate`、`remark`、`nodePrefix`、`bandwidth`、`maxConnections`、`nodeMultiple`、`level`、`periodType` 都不能通过更新清成空。只有 `maxIps`/`downloadMbps`/`uploadMbps` 被特别处理成「可显式清空」（`:399-402`） |
| 所属用户 | `userId` 非空则覆盖；更新路径**不校验用户是否存在** |
| 标签 | 控制器在更新后若 `tagIds != null` 才调用 `updateAccountTags` 整体替换（`AccountController.java:245-248`）。**潜在问题**：反射拷贝会把源对象字段初始化出的空 `tags` 集合覆盖到实体上，可能导致缺省 `tagIds` 的更新清空标签（按代码推断，未实测，问题 4） |
| `authCode` | 请求带了非空值就直接覆盖（任意字符串，不查重）；重新生成走专门操作 §1.7 |

**副作用（改了哪个字段会触发什么）**：

| 变化 | 触发 |
|------|------|
| `accountNo` 变化、`maxIps` 变化、`downloadMbps` 变化、`uploadMbps` 变化 | 提交后异步限速全量同步（`AccountService.java:406-413`） |
| 其他字段（`uuid`、`toDate`、`disabled`、`bandwidth`、`maxConnections`、`nodeMultiple`、标签…） | **无任何下发**，也不置部署位待部署 |
| `accountNo` 变化 | `account_online_ip.account_no` **不会同步更新**，旧记录成孤儿（问题 7） |
| `bandwidth` 变化 | 不更新当前周期 `account_traffic_stats.bandwidth_quota`，而「周期配额优先于账户基准」，本周期内不生效（问题 11） |
| 标签变化 | 不置待部署（§5.3） |

### 1.4 禁用 / 启用

`toggleAccountStatus`（`:452-461`）只改 `disabled` 并返回，**没有**触发限速同步、部署或踢连接。效果：
- 订阅立即拒绝：`isActive()` 为 false，返回「账户已被禁用」（spec-subscription §2.1；`Account.java:103-114`）。
- 节点上的 sing-box 用户仍在，直到下次该节点被部署（`DeploymentDataLoader` 只加载 `disabled=0 且 (toDate 为空或 > now)` 的账户，`DeploymentDataLoader.java:210`；`repository/TagRepository.java` 的 `findActiveAccountsByTagIds`）。需要管理员手动点账户「部署」。
- 防共享、超额等运行时判断对禁用账户的处理见 spec-traffic §3.3、§3.6。

### 1.5 续费 / 延期

入口 `PATCH /api/admin/accounts/{id}/renew?expiryDate=&amount=&paymentMethod=`（`AccountController.java:291-341`），服务 `AccountService.renewAccount`（`:463-477`）。

- **`toDate` 的计算由前端完成**：弹窗默认值 = `max(当前 toDate, now) + 1 个月`，另有 `+N 天/月/年` 快捷按钮（`account.js:949-990`）；后端把 `expiryDate` **原样**写成新的 `toDate`。后端不校验它晚于当前到期或晚于 now（可缩短、可设成过去）。
- **与 `periodType` 的关系**：续期不读也不改 `periodType`、`fromDate`。`periodType` 只决定流量统计周期是按月还是按年切（`TrafficPeriodUtils.java:23-30`）。因为统计周期的边界取自 `toDate` 的日/时刻（月）或月/日/时刻（年），续期到不同的日/时刻会改变以后的重置日（问题 10）。
- **禁用账户被续期会自动启用**：`disabled==1` 时置 0（`:471-473`；`disabled` 为 null 时会 NPE）。
- **不触发任何下发**：对「已过期被夜间任务禁用并从节点移除」的账户，续期只改数据库，必须再手动部署才能重新连上（问题 3）。
- **流量不重置**：续期不动 `account_traffic_stats`。
- **流水**：`amount != null && amount > 0` 才记一笔收入：`type 0`、`businessTable "account"`、`description "账号：N月"`（N 见 §6.3，起点取 `max(旧 toDate, now)`，终点为新 `toDate`）、`remark=账户备注`。填了正金额则 `paymentMethod` 必填且必须在枚举内，错误为 400「填写金额时必须选择付款方式」/「付款方式不合法」。**金额为 0/负数/空时静默不记账**，这与 onboarding 里金额 ≤0 报 400 不一致（问题 9）。
- 缺少/非法的 `expiryDate`：`LocalDateTime.parse` 抛异常，未捕获，返回 500（问题 6）。
- 账户到期后的夜间处理（禁用 + 强制部署）见 spec-traffic §5。

### 1.6 下发相关操作

- **手动部署**：`PATCH /api/admin/accounts/{id}/deploy` → `NodeDeploymentService.deployByAccountIds([id])`：用账户标签找到所有共享标签的节点（`TagRepository.findNodesByAccountIds`），对这些节点强制部署（`service/deployment/NodeDeploymentService.java:61-68`）。账户没有任何标签/节点 → 空结果。
- **限速同步**：账户新增、删除、`accountNo`/`maxIps`/`downloadMbps`/`uploadMbps` 变化触发，事务提交后合并为一轮整机重建（spec-deploy §3.6）。

### 1.7 重新生成 authCode

`resetAuthCode`（`AccountService.java:479-489`）：生成新的 32 位十六进制 `authCode`，只改库。旧订阅链接立即失效（订阅按 `authCode` 查账户），**不需要部署**（`authCode` 不进 sing-box 配置）。账户不存在时抛 `EntityNotFoundException`，管理接口未捕获，返回 500。普通用户通过 `MyAccountController` 也能对自己的账户做同样的事（§4.5）。

### 1.8 删除

`deleteAccount`（`AccountService.java:440-450`）：
- 删 `account_online_ip` 中该 `account_no` 的记录；
- 删 `account_traffic_stats` 中该 `account_id` 的记录；
- 删账户行；`account_tag` 的联结行由 Hibernate 随实体删除（`Account.tags` 是拥有方，推断未实测）；
- 异步限速同步。
- **不处理**：`account_node_subscription_domain_binding`（含 `account_node_subscription_placement`）、`alert_state`、`transactions`。绑定表有 `account_id → account.id` 外键（spec-data §1.11），存在绑定时删除应当失败并返回 500（未确认，Hibernate `schema-management=update` 生成外键，`application.properties:20`）；流水保留且 `businessId` 悬空，展示为「未知账户」（`TransactionService.java:158-161`）；告警状态留孤儿。
- 不踢存量连接，不置部署位待部署；节点配置里的该用户要等下次部署才消失。
- 账户不存在：控制器先查，返回 404。

### 1.9 `speed` 字段的实际状态

`Account.speed`（KB/s，`Account.java:54-57`）：
- 写入：`AccountRequest`、`AccountDto` 都**没有** `speed`；`AccountService.saveAccount/updateAccount` 不设置它。前端会提交 `speed`（`account.js:1123,1165`，默认值 2048）但被 Jackson 忽略。**没有任何代码路径能写入它**。
- 读取：全仓只有 `AccountRepository.findActiveRateLimitedAccounts`（`:31-32`）引用，且没有调用者。
- 结论：它只可能含有历史数据。与 scope 决策 2「`speed` 都在使用」冲突，需要对生产库核实 `speed > 0` 的行数后决定（问题 1）。

### 1.10 与进行中变更 `openspec/changes/simplify-account-creation` 的差异

状态：**代码已落地，变更未归档**。
- 证据：`tasks.md` 全部勾选；`AccountOnboardingService`、`/api/admin/accounts/onboarding`、前端 `account-form-defaults.mjs`/`account.js:1142` 均存在，提交 `e02ad64 feat(account): 简化开户并优化移动端操作`；`AccountOnboardingServiceTest` 存在。`openspec/specs/` 下没有 `account-onboarding`，`specs/account-management` 也没有合入新增的默认值需求，说明没有走归档流程。

与代码的差异：

| 项 | openspec | 实际代码 |
|----|----------|----------|
| 默认最大 IP / 下行 / 上行 | 3 / 20 Mbps / 10 Mbps | 2 / 12 Mbps / 8 Mbps（`account-form-defaults.mjs`） |
| 其他前端默认 | 未提 | `nodePrefix "8"`、前端随机 16 位密码、`speed 2048`（后端丢弃） |
| 账号备注必填 | 前端必填 | 仅前端校验，后端不要求 |
| 首期金额 ≤0 | 报「请输入有效金额」 | 一致，但与续期（≤0 静默忽略）不一致 |
| 级别 `level 4` | 保留 | 决策 12 要求删除 `level` |
| 用户备注名/备注 | 前端移除输入 | 后端 DTO 仍接收（`AccountOnboardingUserRequest`） |
| 错误响应 | 400/409 | 一致 |

`account-management` 主 spec 其余内容（UUID 编辑与校验、`downloadMbps`/`uploadMbps`）与代码一致：UUID 校验在更新路径（§1.1），Mbps 空值表示不限速（`AccountService.java:399-402`）。

---

## 2. 账户状态判定与列表查询

### 2.1 状态口径

没有 `status` 列，全部由 `disabled`、`toDate`、当前时间推导：

| 状态 | 口径 | 依据 |
|------|------|------|
| 有效 `active`（实体方法） | `!过期 且 disabled 为 null 或 0`；过期 = `toDate != null 且 now > toDate`（`toDate == now` 视为有效） | `Account.java:103-114` |
| 有效（列表筛选 `status=active`） | `disabled=0 且 (toDate 为空 或 toDate >= now)` | `AccountService.java:83-86` |
| 已过期 `expired`（筛选） | `disabled=0 且 toDate 非空 且 toDate < now` | `:87-90` |
| 已禁用 `disabled`（筛选） | `disabled=1`（不论是否过期） | `:91-93` |
| 即将到期 `expiring`（筛选） | `disabled=0 且 now < toDate <= now+7 天` | `:94-99` |
| 超额 | **不是状态列**：当期用量 `sum(upload+download) >= 有效配额(GB)×1024³`，有效配额 = 当前周期统计行的 `bandwidth_quota`，为空取 `account.bandwidth`，仍为空或 ≤0 视为不限量；DTO 里给出 `usagePercentage`（封顶 100）和 `trafficOverQuotaLimited`，限速值见 spec-traffic §2 | `AccountService.java:293-315`；`service/AccountTrafficLimitService.java:resolveEffectiveMbps/isOverQuota` |
| 在线 | `account_online_ip.last_online_time > now - airopscat.online.check-minutes`（默认 10 分钟），按 `account_no` 去重 | `AccountOnlineIpService.java:95-102,557-558`；spec-traffic §3.5 |
| 到期剩余天数 | `ChronoUnit.DAYS.between(now, toDate)`（可为负） | `AccountService.java:336-339` |
| 当前统计周期 | 优先取当前周期统计行；没有则按与统计相同的规则由 `toDate`+`periodType` 推算 | `:299-306` |

注意夜间任务（spec-traffic §5）会把已过期账户置 `disabled=1`，所以长期来看「已过期」筛选只会出现夜间任务之前的那一批，之后都是「已禁用」。

顶部统计（`getAccountsStats`，`AccountService.java:200-219`）：`total`、`active`、`onlineUsers` 乘以系统配置 `airopscat.account.multiplier`（默认 1，仅作展示倍率），`expired`/`disabled`/`expiringSoon` 不乘；`expired = toDate < now`（含已禁用）、`expiringSoon = now ≤ toDate ≤ now+7 天`（含已禁用）、`active = disabled=0 且 (toDate 空或 toDate > now)`。与上面的筛选口径不完全一致（问题 12）。

### 2.2 列表筛选与排序

`getAccountPage`（`AccountService.java:59-139`）：

- 固定排序 `createTime` 倒序，**没有排序参数**；分页 `page`（从 1）、`size`（默认 10，无上限）。
- `search`：关键字 trim 后转小写，匹配下列任一（`:141-172`）：`accountNo` 等于/前缀；`remark` 包含；`uuid` 等于/前缀（关键字是合法 UUID 时再加 `uuid` 包含）；关键字长度恰为 32 时加 `authCode` 等于；`userId in (匹配的用户 id)`，用户 id 由 `UserRepository.findIdsByKeyword` 取（`email` 或 `nickName` 等于/前缀、`nickName` 包含，最多 200 个，`repository/UserRepository.java:28-51`）。
- `userId`：精确。
- `status`：`active`/`expired`/`disabled`/`expiring`（忽略大小写，其他值忽略）。
- `onlineStatus`：`online`/`offline`。先取全部当前在线记录的 `accountNo` 集合再做 `in`/`not in`（无在线账户时 `online` 返回空集，`offline` 不加条件）。
- 另有按用户查询 `GET /accounts/user/{userId}`（同样的分页与 `search`）。
- DTO 补充信息（`convertToDtoList`，`:225-343`）：用户邮箱/昵称、当期上传/下载/总用量、有效配额、周期起止、在线连接列表与数量、最后在线时间、到期剩余天数。**在线连接数优先取防共享聚合内存里的值**（`AccountGuardAggregator.snapshotStatsByAccountNos`），否则取在线记录条数；最后在线时间取当前在线记录里最大的，否则取 `account_online_ip` 里的历史最大 `last_online_time`（受清理保留期限制，见 §3）。

Go 版建议：列表筛选/排序/统计口径统一一份（建议以筛选口径为准，统计卡片复用同一个谓词）；增加排序（至少 `toDate`、`createTime`）；`size` 设上限；`multiplier` 视为展示倍率，决定是否保留（scope 未提）。

---

## 3. 账户最近使用节点（决策 8）

**现状：没有这个查询。** 现有的相关查询都只覆盖「当前在线」：
- `GET /api/admin/accounts/online/accountNo/{accountNo}`、`/online/node/{nodeIp}`、`/online/all`（`AccountController.java:376-395`）：只返回 `last_online_time` 在最近 `check-minutes`（默认 10 分钟）内的记录（`AccountOnlineIpService.java:64-71`）。
- 账户列表的「最后在线时间」是 `max(last_online_time)`，没有节点信息（`AccountOnlineIpRepository.findMaxLastOnlineTimeMapByAccountNos`）。
- 在线记录 DTO 已含 `nodeIp`、`nodeId`、`nodeName`、`nodeTag`、`lastOnlineTime`、`sessionStartTime`（`model/dto/AccountOnlineIpDto.java`）。

**数据来源**：`account_online_ip`（spec-data §1.13、spec-traffic §3.5）。写入来自节点 guard agent 的 `guard-sync` 上报：每次上报把「该账户在该节点上的在线 IP/连接」upsert 为行，`node_id` 由 `nodeTag` 映射到「该服务器 IP 上已部署、未禁用、type=0 的节点」，**映射不到时 `node_id` 为 NULL**。要查「账户最近用过哪些节点」，应按 `account_no` 分组到 `(node_id, node_ip)`，取 `max(last_online_time)`，按时间倒序。

**必须先解决的两点**：
1. **保留期**：默认只保留 8 小时（`airopscat.account.online.history.retention-hours` 默认 8，最小 1，`AccountOnlineIpService.java:328-331`），scope 建议 30 天。保留期同时是定时清理的执行间隔（spec-traffic §3.5）。若改为 30 天，表体量会随「每连接一行（有 connection refs 时）」或「每 (账户, 客户端 IP, 节点, 入站 tag) 一行」增长，需按天核算行数，并保证索引 `(account_no, last_online_time)` 可用。
2. **手动清理接口** `DELETE /accounts/online/cleanup` 会删掉超过 `2×check-minutes`（约 20 分钟）的记录（`AccountOnlineIpService.java:357-384`），会清空「最近使用」历史，Go 版应删除该接口或改为只清保留期外的。

另外：账户改名不同步 `account_no`（问题 7）、账户删除会删这些行（§1.8），`node_id` 为 NULL 的行只能用 `node_ip`/`node_tag` 展示。

---

## 4. 用户（User）

### 4.1 角色与账户关系

- 角色是单值字符串 `ADMIN`/`PARTNER`/`VIP`（spec-data §1.1、§3）。后端**不校验取值**，任何字符串都能保存（`UserService.java:156-181` 无枚举检查）。创建时角色为空默认 `VIP`（`:139-141`）。
- 路径权限：`/api/admin/*` 仅 ADMIN；`/api/partner/*`、`/api/vip/*` 分别允许更多角色；其他路径要求登录（`application.properties:62-81`）。
- 一个用户可有多个账户；`account.user_id` 可空、外键 → user。用户列表 DTO 带 `accountCount`（`UserService.java:103`）。
- **用户的启用/禁用只影响登录**（`UserStatusService` 返回匿名身份，spec-data §3），**不影响其名下账户的订阅与节点用户**（订阅只看账户自身状态）。
- `user.referrer` 没有任何代码使用。

### 4.2 创建

`UserService.saveUser`（`:118-147`）：
1. 用户为空 →「用户资料不能为空」。
2. 邮箱 trim + 转小写；为空 →「邮箱地址不能为空」；正则 `^[^\s@]+@[^\s@]+\.[^\s@]+$` 不过 →「请输入有效的邮箱地址」。
3. 按 `lower(email)` 查重，存在 → `UserEmailAlreadyExistsException`「邮箱已存在」（409，继承自 `IllegalArgumentException`，所以需要先捕获子类）。
4. 密码为空或 trim 后长度 <6 →「密码长度至少为 6 个字符」；哈希的是**未 trim 的原文**，bcrypt（cost 10，见 spec-data §3）。
5. 角色默认 `VIP`；`disabled` 默认 0。
6. 响应用 `UserDto`（无密码；含 `failedAttempts`、`lockTime`、`accountCount`）。

### 4.3 修改、改密、禁用、删除

- **修改**（`updateUser`，`:156-181`）：只拷贝非空的 `email`、`nickName`、`remarkName`、`role`、`disabled`、`password`。**不拷贝 `remark`**（备注永远改不了，问题 15）。更新时**不校验也不规范化邮箱、不查重**（唯一冲突抛 `PersistenceException`，500）、**不校验密码长度**。
- **改密**：没有独立接口，也没有用户自助改密；通过管理员 `PUT /api/admin/users/{id}` 带 `password` 重新哈希。改密**不重置**登录失败次数和锁定时间。没有解锁接口（锁定 30 分钟自动过期，见 spec-data §3）。
- **禁用/启用**：只置 `disabled`（`:188-197`）；不踢已登录会话（`UserStatusAugmentor` 每次请求检查，下次请求变匿名）。
- **删除**：`deleteById`，不检查名下账户（有账户时外键失败，500）、不防删最后一个 ADMIN 或删自己（问题 15）。
- **列表筛选**：`search`（`email`/`nickName` 包含，小写）、`role`（等于）、`status`：`active`（`disabled` 为 0 或空，且未处于锁定期）、`locked`（未禁用且 `lock_time` 在 30 分钟内）、`disabled`（`disabled=1`）；固定 `createTime` 倒序；统计 `total/active/locked/disabled`（`:39-80,107-116`）。
- 默认种子用户：没有 ADMIN 时启动创建三类默认账号（见 spec-data §5，密码硬编码在 `config/DataInitializationConfig.java`，问题 16）。

### 4.4 与开户的关系

onboarding 新建用户走 §4.2 同一函数，所以邮箱规则一致；用户与账户在同一事务里创建（§1.2）。

### 4.5 `MyAccountController`（用户自助，第一阶段不实现）

`controller/MyAccountController.java:40-75`，路径 `/api/me/accounts`（刻意不放 `/api/admin` 下，任何登录用户可访问）：
- 列表：当前登录用户（会话里的邮箱 → `getByEmail`）名下全部账户，复用 `AccountDto`，但把 `remark`（管理员备注）置空。DTO 仍含 `uuid`、`authCode`、`accountNo`、用量、在线连接明细（含 `clientIp`、`nodeIp`）。
- 重置订阅链接：`POST /{id}/reset-auth`，非本人账户和不存在**一律 404** 以防探测；成功后写一条普通日志（含邮箱和账号，不写系统日志表）。
- 保留路由分组即可（决策 7）；若以后实现，返回字段应收窄。

---

## 5. 标签（Tag）

### 5.1 数据与模型作用

列见 spec-data §1.3。标签是账户与节点之间的**授权关系**：账户可用的节点 = 与账户共享至少一个标签的节点（`TagService.getAvailableNodesByAccount`，`:286-299`）；节点的 sing-box 用户 = 与节点共享标签的有效账户（`DeploymentDataLoader.java:203-225`）；订阅候选节点同样用它（`SubscriptionService.java:256`）。账户没有标签 → 可用节点为空。

### 5.2 增删改与关联

- **创建**（`saveTag`，`:93-100`）：`disabled` 空默认 0；`name` 唯一但不预检，冲突/为空走 `PersistenceException`（500）。
- **修改**（`updateTag`，`:102-117`）：`name/description/color/disabled` 整体覆盖（包括置空）；`disabled` 被写成 null 时「是否启用」判断与 `findByDisabled(0)` 不一致。
- **删除**（`deleteTag`，`:119-129`）：不检查占用，直接 `deleteById`；代码注释称关联会级联删除，但 `Tag` 没有集合映射（`model/entity/Tag.java:29-33` 已注释掉），`account_tag`/`node_tag` 又有指向 `tag.id` 的外键（spec-data §1.3），所以**标签仍被使用时应当删除失败（500，未确认）**，Go 版需明确：禁止删除在用标签，或级联清理关联后再删。
- **启用/禁用**：只改 `disabled` 标志。
- **关联操作**（均先校验账户/节点和标签存在，不存在抛 `EntityNotFoundException`，TagController 没有捕获，返回 500 HTML）：
  - `addTagToAccount`/`addTagToNode`：已关联则忽略（幂等）；
  - `removeTagFromAccount`/`removeTagFromNode`：直接删关联；
  - `updateAccountTags`/`updateNodeTags`：先删全部再插（整体替换）。`updateNodeTags` 用 `list.size() != tagIds.size()` 校验，tagIds 含重复会被误判为「不存在」；`updateAccountTags` 校验会去重但插入时遍历原列表，重复会违反联结表主键（问题 17）。
- **查询**：标签分页（`search` 对 name/description 小写包含、`disabled` 过滤，`createTime` 倒序）；全部/仅启用标签列表；标签下的账户/节点；账户或节点的标签；账户可用节点；节点已授权账户；统计 `total/enabled/disabled`（`TagService.java:32-60,282-322`）。DTO 的 `nodeCount`/`accountCount` 为每个标签单独计数（N+1）。

### 5.3 标签变化的副作用（是否置待部署、是否下发）

| 操作 | 置部署位待部署 | 下发 | 依据 |
|------|---------------|------|------|
| 账户打/删/整体替换标签（`AccountController` 创建/更新、`TagController` 账户标签接口） | **否** | 否 | `TagService.java:176-207,234-253` 没有调用任何部署逻辑 |
| 节点标签：节点编辑接口（`NodeController.updateNode`）里标签集合变化 | **是**（标签进入「实质变更」签名，`hasSubstantialChanges`） | 否（只置位，等部署） | `NodeService.java` `hasSubstantialChanges`/`buildSubstantialChangeSignature`，`markNodePendingDeploy`（`:816-825`） |
| 节点标签：`TagController` 的 `addTagToNode`/`removeTagFromNode`/`updateNodeTags` | **否** | 否 | `TagService.java:144-174,209-232` |
| 批量节点标签（`POST /api/admin/nodes/batch-tags`） | **是**，仅「有效标签集合真的变了」的节点 | 否 | `NodeService.java:519-573` |

`markPendingDeploy` 对 `PENDING_DELETE` 的部署位不动（`:820-825`）。账户标签变化影响哪些节点的用户列表，但不会通知任何节点，需要管理员手动部署（账户「部署」或节点「部署」）。落地节点被代理节点引用为出站时，标签变化**不**影响引用它的代理节点（`NodeService.java` 注释「标签只影响本节点用户」）。

### 5.4 标签 `disabled` 没有任何业务效果

授权与部署相关的查询都不过滤 `tag.disabled`：`TagRepository.findByAccountId`、`findByNodeId`、`findTagIdsByNodeIds`、`findAccountIdsByTagIds`、`findNodesByAccountIds`（后者被到期任务、手动部署使用）。它只影响「仅启用标签」下拉与统计卡片。Go 版要么真的实现「禁用标签 = 暂停该标签授权」（需要同时定义是否触发置待部署），要么删掉该字段（问题 17）。

### 5.5 批量节点标签调整（`openspec/specs/node-batch-tag-adjustment`）

`NodeService.batchUpdateNodeTags`（`:519-573`），请求 `nodeIds`、`tagIds`、`mode`：
- `mode` 空 = `REPLACE`；不区分大小写；非法值 400「不支持的批量标签调整模式」（`model/enums/NodeBatchTagUpdateMode.java:18-28`）。
- `nodeIds` 为空（含只含 null）→ 400「请选择要调整标签的节点」；`nodeIds` 去重去 null；任一节点不存在 → 404 语义「部分节点不存在，无法批量调整标签」；`tagIds` 去重去 null，任一标签不存在 →「部分标签不存在，无法批量调整」。
- `REPLACE`：目标集合 = tagIds（**tagIds 为空会清空所选节点的全部标签**，允许）；`APPEND`：目标 = 当前 ∪ tagIds。
- 目标与当前相同的节点计入 `unchangedCount`，不置位；不同的写入新标签集合并 `markNodePendingDeploy`，计入 `updatedCount`。
- 在同一事务内（`@Transactional`）；返回 `{updatedCount, unchangedCount}`。
- 与主 spec 一致，代码无偏差。

---

## 6. 收支记账（Transaction）

表见 spec-data §1.17。

### 6.1 字段规则

| 字段 | 规则 |
|------|------|
| `type` | 0=收入、1=支出（`model/enums/TransactionType.java`）。后端**不校验取值**；净额 SQL 把「非 0」都当支出（`TransactionRepository.getNetBalance`） |
| `amount` | `BigDecimal`，必填（列非空）。通用 CRUD **不校验正负**（可为 0 或负）；onboarding 要求 >0；续期 ≤0 视为不记账 |
| `transaction_date` | 创建时为空取 now（`TransactionService.java:174-183`） |
| `payment_method` | 通用 CRUD **不校验**；账户开户/续期、域名/服务器续期入口会校验必须 ∈ `WeChat`/`AliPay`/`BankCard`（区分大小写） |
| `business_table`、`business_id` | 见 §6.2；通用 CRUD 不校验取值和关联行是否存在 |
| `description`、`remark` | 自由文本 |
| 修改 | 只拷贝非空字段（`:199-209`），**不能把 `description`/`remark`/`paymentMethod`/`businessTable` 清空**；删除无检查 |

### 6.2 `businessTable`/`businessId` 实际取值

| `business_table` | 写入处 | `type` | `description` | `remark` | `business_id` |
|------------------|--------|--------|---------------|----------|---------------|
| `account` | 开户 `AccountOnboardingService.java:158-167`；续期 `AccountController.java:326-337` | 收入 0 | `账号首期：N月` / `账号：N月` | 账户备注 | 账户 id |
| `domain` | `DomainController.java:186-190` | 支出 1 | `域名：N月` | 域名备注 | 域名 id |
| `server` | `ServerController.java:381-385` | 支出 1 | `服务器：N月` | 服务器备注 | 服务器 id |

通用 `POST /api/admin/transactions` 可手工新增任意记录（含 `business_table` 为空的无关联流水）。`GET /transactions/business-tables` 返回的固定清单就是 `account`/`domain`/`server`。展示用 `businessName`：账户取 **`remark`**（DTO 注释写的是 uuid，与实现不符）、域名取域名、服务器取 `ip (name)`，找不到分别是「未知账户/未知域名/未知服务器」，其他表名「未知关联业务」（`TransactionService.java:156-172`）。

### 6.3 描述里的月数 N

`AccountTransactionDescriptionUtils.calculateMonths`（`util/AccountTransactionDescriptionUtils.java:10-20`）：起止任一为空或终点不晚于起点 → 1；否则用 `YearMonth` 做**日历月差**（不看日号），最小 1。账户首期起点 = `fromDate`，续期起点 = `max(旧 toDate, now)`。域名/服务器续期各自内联了同样的算法（`DomainController.java`、`ServerController.java` 里 `months` 计算，`<1` 取 1），Go 版应合并成一个函数。

### 6.4 查询与统计口径

- 分页筛选（`TransactionService.java:44-95`）：`search`（小写后对 `description`/`remark`/`paymentMethod` 包含）、`type`、`businessTable`、`businessId`、`startDate`、`endDate`（均包含边界，作用于 `transaction_date`）；固定按 `transaction_date` 倒序。`startDate`/`endDate` 解析失败未捕获（500）。另有按业务（表+id）查询。
- 汇总 `getTransactionStats`（`:105-127`）：`totalIncome=sum(type=0)`、`totalExpense=sum(type=1)`、`netBalance=sum(收入 − 其余)`，空值按 0；`currentMonthIncome/Expense` 为本自然月（`[月初 00:00:00, 月末 23:59:59]`，`between`）的收入/支出合计。
- 月度统计 `getMonthlyStats(months)`（`:221-246`）：最近 N 个自然月（默认 6，不设上限，N≤0 返回空），按时间正序；每项 `month` 标签是英文大写月名加年份（`JULY 2026` 这种，`Month.toString()`）、`income`、`expense`、`balance`。同样用 `between 月初..月末 23:59:59`，漏掉最后一秒内的小数秒记录。
- 没有按付款方式、业务表的汇总。金额用 `BigDecimal`，SQLite 落库建议见 spec-data §6.1。

---

## 7. 订阅定制域名绑定（account_node_subscription_domain_binding）

表见 spec-data §1.11。openspec 主 spec（`openspec/specs/account-node-subscription-domain-binding`）已**过时**：它写的是「选一个 DNS 记录，节点订阅域名覆盖」，现代码是「**必须选一个部署位，DNS 记录可选**」。以下以代码为准。

### 7.1 基数与对象

- 唯一键 `(account_id, node_id)`：**一个账户在一个节点上最多一条绑定**；同账户可在多个节点上各有一条，同节点可有多个账户的绑定。
- 一条绑定 = 「订阅里该节点展示哪一个部署位」（必选，且**只能选一个**，存在 `account_node_subscription_placement` 里）+「可选的连接域名」（`domain_dns_record_id`，为空表示不覆盖连接地址，只定制部署位）。
- `enabled` 默认 1（`@PrePersist` 补齐，也可由请求带入，不校验 0/1），`remark` trim，空串存 null。
- 它**不**决定账户能用哪些节点（仍由标签授权），也**不**进节点部署配置和快照；创建/修改/启停/删除都**不**置待部署（spec 与代码一致）。

### 7.2 保存规则（`AccountNodeSubscriptionDomainBindingService.save`，`:84-126`）

校验顺序与错误（HTTP：`EntityNotFoundException`→404，`IllegalArgumentException/IllegalStateException`→400，响应 `{message}`；唯一键冲突不会被捕获，500）：
1. `validateRequest`：请求、`accountId`、`nodeId` 必填，`nodeServerId` 必填「请选择展示的部署位」。
2. 账户存在（404「账户不存在」）、节点存在（404「节点不存在」）。
3. 节点必须属于账户：账户标签 ∩ 节点标签（400「节点不属于该账户」，`:269-276`）。
4. 部署位必须属于该节点且非 `PENDING_DELETE`（400「部署位不属于该节点」，`:249-255`）。
5. 带 `id` 时：绑定必须存在（404「账户节点订阅域名绑定不存在」）、必须属于该账户（400「绑定不属于该账户」）、同 `(账户,节点)` 不能有另一条（400「该节点已有订阅定制」）；不带 `id` 时按 `(账户,节点)` upsert，**已有就更新而不是报错**。
6. 解析连接域名（`resolveRecordId`，`:128-147`）：
   - 带 `newDomainId`：新建 A 记录并绑定（`ServerDomainService.createARecord`）。子域名转小写，需匹配 `^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$`，否则 400「新建域名需选择域名并填写合法的子域名」；记录内容为所选部署位服务器的 IP，TTL 1（自动），不代理；同域名下同名记录已存在 → 400「已存在同名记录 X，请选择已有记录」；记录先以待创建入库，事务成功提交后再推送到 DNS 服务商，推送失败只记警告，保持待创建，订阅在同步成功前使用部署位默认地址。
   - 带 `domainDnsRecordId`：若与原绑定相同且仍属于同一服务器/节点/部署位则保持；否则走 `bindExisting`：记录必须存在（400「所选域名记录不存在」）、类型为 A、状态不是 `PENDING_DELETE`、未开启时段计划、不与其他部署位 `accessHost` 或其他账户绑定「不同节点/不同部署位」冲突（可共用：同节点、同一个部署位的多条绑定，以及该部署位自己的 `accessHost`）；若记录未归属服务器，其内容必须等于该服务器 IP；通过后把记录的 `server_id` 置为该服务器。
   - 都不带：不覆盖连接地址（`domain_dns_record_id=null`）。
7. 写入：覆盖节点、记录、部署位集合（先清再加一个）、`enabled`、`remark`；若记录变了，释放旧记录（无人引用则 `server_id` 置空，`releaseIfUnused`）。

启停 `setEnabled` 只改标志；删除 `delete` 删绑定后同样 `releaseIfUnused`。

### 7.3 订阅里怎么用

- 覆盖域名（`findSubscriptionHostOverrides`，`:165-195`）：只取 `enabled=1` 的绑定；记录必须存在且**状态为 `SYNCED`** 且 `fullName` 非空才覆盖，否则回落部署位默认地址。
- 部署位选择（`findSubscriptionPlacementSelections`，`:200-208`）：返回节点 id → 展示部署位集合，只取启用绑定；具体如何影响订阅条目见 `spec-subscription.md` §3.3-3.4。
- 候选可选节点接口（`searchAvailableNodes`，`:210-232`）：账户标签授权的节点，按名称小写排序，可按名称/服务器 host/IP/协议/备注包含过滤，`size` 默认 20、≤0 取 20、上限 100。

### 7.4 与流量阈值切换的联动

联动细节见 `spec-traffic.md` §4.2：`enabled=1` 且 `domain_dns_record_id` 非空的绑定属于「可切换域名引用」，归属其（唯一的）部署位；阈值切换改写 DNS A 记录内容，**不改绑定本身**。本模块只需保证：绑定引用的记录属于所选部署位的服务器（`server_id`）、类型 A、非时段计划，这是阈值切换的前置条件。DNS 记录被绑定引用时的删除保护（同步/手动删除/解绑域名的拒绝与标记 `PENDING_DELETE`）属于 DNS 模块，规则见 openspec 主 spec 的最后一个需求，不在本文展开。

### 7.5 其他

- 节点或部署位被删除时用原生 SQL 清理 `account_node_subscription_placement`（`service/NodeService.java:795`、`service/NodePlacementSyncService.java:157`）；**绑定行本身**删节点时的处理未确认。
- 账户被删除时不清理绑定（§1.8）；账户失去标签授权后绑定残留但订阅忽略它（订阅按标签授权求候选，再看绑定），仍会占用 DNS 记录的 `server_id` 与阈值切换对象（问题 20）。

---

## 8. 系统日志（SystemLog）

表见 spec-data §1.16。

- **分类**：枚举 `SystemLogCategory` 只有 `SERVER_FAILOVER`（标签「服务器切换」，`model/enums/SystemLogCategory.java:6-8`）。`level` 取 `INFO`/`WARN`/`ERROR`。
- **写入点**：全仓只有 `service/ServerFailoverService.java`：

| 位置 | level | resource_id / resource_name | 触发 | 写入方式 |
|------|-------|----------------------------|------|----------|
| `:108` | WARN | null / null | 某域名所属节点的所有部署服务器流量均超阈值，保持当前解析 | 独立事务 |
| `:147` | WARN | DNS 记录 id / 记录完整域名 | 时段计划记录同上 | 独立事务 |
| `:161` | INFO | DNS 记录 id / 完整域名 | 时段计划记录发生切换 | 加入调用方事务 |
| `:202` | INFO | DNS 记录 id / 完整域名 | 阈值/便宜时段/回切推送成功 | 加入调用方事务 |
| `:208` | ERROR | DNS 记录 id / 完整域名 | 推送失败（`message` 追加「；失败：原因」） | 独立事务 |

- **`resource_id` 实际是 DNS 记录 id**，`SystemLog.java:36` 的注释写的是「如节点」。
- **`detail` 结构**（`ServerFailoverService.java:293-301` + `:195-199`）：JSON 对象，键 `reason`（`CHEAP_WINDOW`/`THRESHOLD`/`RESET`）、`nodeId`、`homeServerId`、`toServerId`、`fromContent`、`toContent`、`homeUsage`、`toUsage`，写入时追加 `success`（成功 true、失败 false）。超阈值告警类日志 `detail` 为空（不写）。`detail` 为空或空 map 时存 null。
- **写入约束**（`SystemLogService.java:33-56`）：`message` 截断到 512，`resource_name` 截断到 257；`record` 加入调用方事务（业务回滚日志也回滚）；`recordIndependently` 用新事务，写入失败只记警告不抛出。
- **查询**（`SystemLogRepository.findByQuery`、`SystemLogService.getPage`，`:58-67`）：`category` 等于；`level` 转大写后等于；`keyword` 小写后包含于 `message` 或 `resource_name`；`startTime`/`endTime` 包含边界，格式 `yyyy-MM-dd'T'HH:mm[:ss]` 或空格分隔，分钟精度自动补 `:00`，解析失败返回 400「时间格式不正确」；排序 `create_time` 倒序、`id` 倒序；页码最小 1，`size` 夹在 1–100（默认 20）；返回 `{records,total,pages,current,size}`。另有分类列表接口（`value`=枚举名、`label`）。
- **清理**：`airopscat.system-log.retention-days`（默认 90，最小 1）之前的删除（`SystemLogService.java:69-73`），由 `SystemLogCleanupTask` 周期执行；Go 版并入统一保留期任务（scope §6）。
- **没有审计日志**：账户、用户、标签、记账、部署、告警都不写系统日志；现有部署结果落在 `node_deployment`，告警落在 `alert_state` + Bark 推送。scope §6 说「统一记录部署、告警等事件」，目前并未实现，需要决定新增哪些 category（建议至少 `DEPLOY`、`ALERT`，其余按需）。

---

## 9. 账户流量统计的对外查询（`AccountTrafficStatsService`，只列查询/维护部分）

写入与周期规则见 spec-traffic §1.3-1.4，这里只列管理侧的查询和维护：

- **列表**（`:83-114`，`GET /api/admin/traffic-stats`）：`search` 对**账户备注**小写包含；`startDate`/`endDate` 取周期**重叠**（`period_end >= start 且 period_start <= end`）；排序 `sortBy=totalBytes`（默认，上传+下载，`id` 倒序打破平局）否则只按 `id` 倒序，方向 `asc`/`desc`；返回 id、userId、账户备注、accountId、周期、上传、下载、合计、配额。
- **汇总**（`:116-132`）：同筛选下上传、下载总量，另给「B/KB/MB/GB」格式化文本。
- **有效配额**：`getEffectiveBandwidth`（`:195-205`）= 当前周期第一条记录的 `bandwidth_quota`，空则取 `account.bandwidth`，仍空为不限量。「当前周期」= `period_start <= now < period_end`，多行时「第一条」无排序（`AccountTrafficStatsRepository.findByAccountIdAndCurrentTime`）；账户列表的用量是**对这些行求和**，配额却取其中一条（问题 10）。
- **维护接口**：新增、整行更新（只拷非空字段，设 0 是合法值）、`PATCH .../quota`（设置某行的 `bandwidth_quota`，传空表示回退到账户基准）、删除。创建只校验 `userId` 对应用户存在。**没有「重置」接口**；要「清零当期用量」只能手工把当前行的上传/下载改成 0 或删除该行（删除后下次采集会按账户的 `toDate`/`periodType` 重新建一行并取 `account.bandwidth` 为配额）。
- 账户删除时按 `account_id` 全删（§1.8）；保留期清理见 `cleanupExpiredStats`（`:295-317`，`airopscat.account.traffic.retention-days` 默认 180）。

---

## 10. 现有问题清单（供后续决策）

> 处理决定（2026-10-19，见 `scope.md` 决策 24）：全部按各条建议修复；第 11 节 D1–D8 按建议处理。

编号供 `scope.md` 决策记录引用，写法参照 `spec-deploy.md` §7、`spec-traffic.md` §8。严重度：高=影响正确性/数据，中=行为不一致，低=清理。

| # | 严重度 | 问题 | 依据 | 建议 |
|---|--------|------|------|------|
| 1 | 高 | `account.speed` 无写入也无读取路径，与决策 2「speed 在用」不符；前端默认值 2048 提交后被丢弃 | `AccountRequest.java`、`AccountDto.java` 无该字段；`AccountRepository.java:31-32` 仅有未被调用的查询 | 先查生产库 `speed>0` 行数；无则删列并从导入中丢弃，有则恢复写入入口并更新决策 2 |
| 2 | 低 | `level` 仍被开户校验、前端默认 4，订阅/部署不读 | `AccountOnboardingService.java:77`；spec-subscription §2.2 | 按决策 12 删除，同时删校验与前端默认 |
| 3 | 高 | 账户的 `uuid`/`disabled`/`toDate`（续期）/标签 变更、删除账户，都不置待部署也不下发；续期已被夜间任务移出节点的账户后仍连不上，禁用/删除后用户仍留在节点配置里直到下次部署 | `AccountService.java:406-413,452-477`；`AccountController.java:265-289,324`；`DeploymentDataLoader.java:210` | 重写时定义统一规则：账户影响节点用户集合的变化（启停、到期、续期、`uuid`、标签、删除）自动把相关节点的部署位置为待部署（或自动合并推送） |
| 4 | 高 | 更新账户用反射非空拷贝：不能清空 `toDate`/备注/`nodePrefix`/`bandwidth`/`maxConnections`；`tags` 默认空集合被拷贝，缺省 `tagIds` 时可能清空标签（按代码推断，未实测） | `AccountService.java:399-402,505-524`；`Account.java:84` | Go 版用显式「全量覆盖」或明确字段的 PATCH 语义，标签单独操作 |
| 5 | 中 | 旧创建接口无校验；`uuid` 格式、`accountNo`/`uuid` 唯一性、`authCode` 唯一性创建时都不检查；`authCode` 无唯一约束且 `findByAuthCode` 取第一条，重复会串号；`accountNo` 冲突返回 500 | `AccountController.java:153-184`；`AccountService.java:345-379`；`AccountRepository.java:43-45` | 只保留 onboarding；`accountNo`、`authCode` 加唯一约束与预检并返回 409；导入前查重 |
| 6 | 中 | 异常映射不一致：账户/标签接口里的 `EntityNotFoundException`、`DateTimeParseException`、`PersistenceException` 未捕获，经 `GlobalExceptionMapper` 变成 500 的 HTML 页 | `TagController.java` 全部关联接口；`AccountController.java:299,349-355`；`config/GlobalExceptionMapper.java` | 统一 JSON 错误体与状态码映射（404/400/409） |
| 7 | 中 | 修改 `accountNo` 不同步 `account_online_ip.account_no`（以及按 accountNo 关联的数据）；`accountNo` 又是 sing-box 用户名，只触发限速同步 | `AccountService.java:388-413`；`DeploymentDataLoader.java:238` | 禁止修改，或修改时同事务迁移关联并置待部署 |
| 8 | 高 | 删除账户不清理订阅定制绑定、`alert_state`；有绑定时按外键推断删除失败（未确认）；流水悬空；不置待部署 | `AccountService.java:440-450` | 明确级联：绑定、告警状态随账户删除；流水保留；节点用户移出走问题 3 的统一规则 |
| 9 | 中 | 续期：新到期时间不校验（可缩短/过去）；金额 ≤0/空静默不记账，onboarding 里 ≤0 报 400；`disabled` 为空会 NPE；`LocalDateTime.parse` 未捕获 | `AccountController.java:299,310-337`；`AccountService.java:471` | 校验新 `toDate` 晚于 now（允许缩短需显式确认）；两处金额规则统一；`disabled` 非空默认 0 |
| 10 | 中 | 统计周期边界取自 `toDate` 的日/时刻，续期到别的日会移动重置日，周期行可能重叠；当期用量对重叠行求和，配额取任意一行 | `util/TrafficPeriodUtils.java:23-60`；`AccountTrafficStatsRepository.java:36-62` | 周期锚点独立存储（如 `period_anchor`），或续期时固定锚点；当期行唯一约束 |
| 11 | 中 | 修改 `account.bandwidth` 不更新本周期统计行的 `bandwidth_quota`（周期配额优先），本周期内无效且不触发同步 | `AccountService.java:293-297`；`AccountTrafficStatsService.java:289` | 改配额时同步更新当前行（或取消「行内配额」），并触发超额重判（spec-traffic §2） |
| 12 | 中 | 状态口径不一致：筛选 `expired/expiring` 排除已禁用，统计卡片不排除；`active` 边界 `>=` 与 `>` 不同；`total/active/onlineUsers` 乘 `multiplier` 而其余不乘；夜间任务把过期账户置禁用后「已过期」基本为空 | `AccountService.java:79-103,200-219`；`AccountRepository.java:55-69` | 统一用一套谓词；`multiplier` 是否保留需确认 |
| 13 | 低 | 列表无排序参数、`size` 无上限；在线筛选把全部在线记录读进内存 | `AccountService.java:61,105-130`；`AccountController.java:71` | 增加排序，`size` 上限（如 100） |
| 14 | 高 | 「最近使用节点」缺失，且 `account_online_ip` 默认只留 8 小时、手动清理接口只留约 20 分钟，与决策 8 要求的窗口（建议 30 天）冲突 | `AccountOnlineIpService.java:328-331,357-384` | 新增按账户分组的查询；保留期设 30 天；删除手动清理接口；评估行数增长 |
| 15 | 中 | 用户：更新不拷贝 `remark`；更新不校验/规范化邮箱、不查重；改密无长度校验；角色无枚举校验；无自助改密/解锁；删除用户不检查名下账户且可删最后一个 ADMIN/自己；禁用用户不影响其账户 | `UserService.java:156-197`；`UserController.java:79-102` | 补齐校验；角色限定枚举；删除前检查账户与最后管理员；明确「禁用用户是否同时停用其账户」；`referrer` 删除 |
| 16 | 高 | 默认种子账号的口令硬编码在源码里（见 spec-data §5），用于首次启动 | `config/DataInitializationConfig.java` | Go 版首次启动时随机生成管理员口令并只输出一次，或强制首次登录改密 |
| 17 | 中 | 标签：`disabled` 无业务效果；`name` 唯一冲突/空值走 500；`updateTag` 可把 `disabled` 写成 null；删除不检查占用（外键推断失败）；重复 `tagIds` 在 `updateNodeTags`/`updateAccountTags` 引发误判或主键冲突 | `TagService.java:93-129,209-253`；`TagRepository` 授权类查询 | 决定 `disabled` 去留；输入去重；删除前检查或级联；唯一冲突返回 409 |
| 18 | 中 | 标签变化的置位不一致：账户标签任何变化、`TagController` 的节点标签接口不置待部署；只有节点编辑和批量接口置位 | 本文 §5.3 | 与问题 3 合并规则：账户标签、节点标签任何变化都置相关部署位待部署 |
| 19 | 中 | 记账：通用 CRUD 无校验（金额正负、`type`、付款方式、`businessTable/businessId`）；更新不能清空字段；净额把非 0 `type` 都当支出；月统计 `between ..23:59:59` 漏小数秒、月份标签英文大写；账户删除后流水悬空；域名/服务器/账户三处各算一遍月数 | `TransactionService.java:105-246`；`TransactionRepository.java:31-42`；`DomainController.java:170-195`；`ServerController.java:365-400` | 校验 `type∈{0,1}`、`amount>0`、`paymentMethod` 枚举、`businessTable` 枚举且 `businessId` 存在；统计改半开区间 `[月初, 下月初)`；月数函数合并 |
| 20 | 中 | 订阅定制：openspec 主 spec 过时（未写部署位必选）；唯一键冲突未捕获（500）；`enabled` 不校验 0/1；账户失去标签授权后绑定残留并占用 DNS 记录与阈值切换对象；删除节点时绑定行的处理未确认 | `AccountNodeSubscriptionDomainBindingService.java:84-126`；`AccountNodeSubscriptionDomainBindingController.java:68-77` | 更新 openspec；标签变化时清理或标记失效的绑定；唯一冲突映射 409 |
| 21 | 低 | 系统日志：`resource_id` 实际是 DNS 记录 id（注释写节点）；只有一个分类；scope 所说「统一记录部署、告警」未实现 | `SystemLog.java:36`；`ServerFailoverService.java` 全部写入点 | 明确分类清单并给每类约定 `detail` 结构；审计类事件（账户/用户/标签/记账）是否纳入需决定 |
| 22 | 低 | 订阅：shadowrocket 的 `getConfigUrl` 对 `remark` 为空的账户 NPE | `SubscriptionService.java:221-223` | 备注空时回退 `accountNo` 或做必填 |
| 23 | 低 | onboarding 后端不要求账号备注，也不给 `fromDate/toDate` 默认值：API 直调缺 `toDate` 即永不过期，周期按每月 1 日切 | `AccountOnboardingService.java:55-96`；`TrafficPeriodUtils.java:46-58` | 后端补默认或必填，与前端一致 |
| 24 | 低 | openspec 与代码偏差：默认值（3/20/10 vs 2/12/8）、变更未归档 | §1.10 | 归档变更并改 spec，或按 spec 改默认值 |

---

## 11. 需要决策的事项

| # | 问题 | 建议 |
|---|------|------|
| D1 | 问题 1：`speed` 去留 | 查生产库；无数据则删 |
| D2 | 问题 3/18：账户或标签变化是否自动置待部署 | 自动置位，部署仍由管理员触发或按统一批量推送 |
| D3 | 问题 8：删除账户的级联范围 | 绑定、告警状态跟随删除；流水保留 |
| D4 | 问题 14：`account_online_ip` 保留期与「最近使用节点」查询 | 保留期 30 天；按 `(account_no, node_id)` 聚合查询 |
| D5 | 问题 17：标签 `disabled` | 删除字段；若保留需定义语义与置位 |
| D6 | 问题 15：禁用用户是否连带停用其账户 | 第一阶段保持现状（不连带），记入扩展点 |
| D7 | 问题 19/21：记账校验强度、系统日志分类 | 记账按建议校验；日志先落 `SERVER_FAILOVER`、`DEPLOY`、`ALERT` |
| D8 | 问题 12：统计展示倍率 `airopscat.account.multiplier` | 删除 |

## 未确认事项汇总

- 生产库 `account.speed > 0` 的行数（问题 1）。
- Hibernate 生成的外键是否真的存在于生产库：影响删除账户（有绑定）、删除标签（在用）、删除用户（有账户）的失败行为（问题 8、15、17）。
- 更新账户时反射拷贝是否真的清空 `account_tag`（问题 4，需要在开发库实测）。
- `account_tag`/`node_tag` 联结行是否由 Hibernate 随账户实体删除（§1.8）。
- 节点删除时对订阅定制绑定行的处理（§7.5）。
- `airopscat.account.multiplier` 的实际使用情况（问题 12）。
