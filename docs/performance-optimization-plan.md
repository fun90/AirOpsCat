# AirOpsCat 性能优化清单与实施计划

## 1. 目标与范围

本文档基于当前仓库实现整理高收益性能优化方向，重点覆盖以下链路：

- Quarkus Web 请求处理
- Hibernate ORM / Panache 查询
- 程序化定时任务与阻塞线程池
- SSH、部署、流量采集等重阻塞任务
- Qute 模板渲染
- Tabler UI、petite-vue、静态资源加载

约束遵循当前项目风格：

- 优先复用现有 Service、Repository、Controller 方法
- 避免大规模推翻现有模块边界
- 优先选择低风险、收益高、可分阶段上线的优化

## 2. 当前项目里的性能信号

结合当前代码，可以看到几个比较明确的热点：

### 2.1 列表页存在重复查询

以节点列表为例：

- [NodeController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/NodeController.java) 在分页查询后，除了执行 `list` 和 `count`，还会额外调用 `nodeService.getNodesStats()`
- [NodeService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/NodeService.java) 的 `getNodesStats()` 内部又会触发多次 `count`

这意味着一个列表页请求可能包含：

- 1 次分页数据查询
- 1 次总数查询
- 4 到 5 次统计查询

仓库中多个控制器也有相同模式，这类重复访问非常容易成为高频瓶颈。

### 2.2 任务型流量集中落到同一个阻塞线程池

- [BlockingTaskExecutorConfig](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/config/BlockingTaskExecutorConfig.java) 默认 `core=2`、`max=8`、`queue=64`
- [NodeDeploymentService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java)、[ServerMonitorTask](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/scheduler/ServerMonitorTask.java)、[DatabaseBackupService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/DatabaseBackupService.java) 等都在复用这个线程池

这说明部署、监控采集、备份、更新检查等任务会竞争同一批线程，在服务器数量增加后容易互相挤占。

### 2.3 数据库热点表索引声明偏少

从实体定义看：

- [Node](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/Node.java) 没有显式索引
- [Server](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/Server.java) 没有显式索引
- [AccountOnlineIp](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/AccountOnlineIp.java) 只有唯一约束
- 当前显式复合索引较明确的是 [ServerMonitorStats](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/ServerMonitorStats.java)

但 Repository 中频繁使用以下条件：

- `serverId`
- `expireDate`
- `disabled`
- `deployed`
- `type`
- `port`
- `periodStart/periodEnd`
- `nodeGroup`

这类“高频过滤字段无明确索引”的组合，通常是最直接的数据库优化切入点。

### 2.4 存在不利于索引命中的搜索写法

[NodeService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/NodeService.java) 的分页搜索使用了较多：

- `lower(...) like`
- 子查询
- `lower(trim(coreType))`

这类写法对功能是友好的，但对 MySQL 索引命中通常不友好，数据量上来后退化会很明显。

### 2.5 服务端模板存在双次渲染

[HomeController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/HomeController.java) 当前是：

- 先渲染内容模板为 `String`
- 再通过 `RawString` 填入布局模板再次渲染

这种方式实现简单，但每次进入控制台页面都要做两次模板渲染，且 `menuGroups` 等布局数据是高频重复计算对象。

### 2.6 静态资源还有进一步瘦身空间

从 [layout.html](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/layout.html) 和 `static/js` 目录看：

- 存在外部字体加载
- 存在第三方 CDN CSS
- `apexcharts.js` 体积约 576 KB

这里有一个推断：
如果图表资源或外部资源被全局加载，而不是按页面按需加载，会直接拉高首屏加载成本。这个点需要在实施前再做一次页面级核对。

## 3. 高收益优化项

以下按“投入产出比”排序。

### 3.1 第一优先级：减少列表页重复查询

#### 建议

将“分页数据”和“统计卡片”拆开，避免每次翻页都重复查询统计。

#### 适合当前项目的做法

- 保留现有 `/stats` 风格接口，前端首屏单独请求统计数据
- 列表接口仅返回分页数据，统计统一通过独立 `/stats` 接口获取
- 优先复用现有 `getNodesStats()`、`getServerStats()` 等方法，不重写统计逻辑

#### 收益

- 降低分页列表接口 SQL 次数
- 列表翻页、搜索、排序时响应更稳定
- 改动集中在 Controller 层，风险较低

#### 适用范围

- 节点
- 服务器
- 账户
- 域名
- 标签
- 交易等带有 `records + total + stats` 模式的页面

### 3.2 第一优先级：补齐数据库索引

#### 建议

优先为高频过滤、排序、关联字段补索引，先解决最容易产生全表扫描的点。

#### 优先补齐的表与字段

`node`

- `server_id`
- `access_host_id`
- `out_id`
- `type`
- `core_type`
- `deployed`
- `disabled`
- `node_group`
- `(server_id, port)`
- `(type, core_type)`

`server`

- `disabled`
- `external`
- `expire_date`
- `supplier`
- `(disabled, external, expire_date)`

`account_online_ip`

- `account_no`
- `node_ip`
- `last_online_time`
- `(account_no, last_online_time)`

`account_traffic_stats`

- `account_id`
- `(account_id, period_start, period_end)`

`server_traffic_stats`

- `server_id`
- `(server_id, period_start, period_end)`

#### 适合当前项目的做法

- 优先通过 JPA `@Table(indexes = ...)` 声明
- 让 `schema-management.strategy=update` 帮助开发环境演进
- 生产环境单独补充 DDL 变更脚本，避免上线时隐式变更不可控

#### 收益

- 直接提升列表页、统计页、定时任务扫描效率
- 对现有业务代码入侵最小

### 3.3 第一优先级：给阻塞任务做分类限流

#### 建议

不要让部署、备份、监控采集、通知检查长期共用同一个小线程池。

#### 适合当前项目的做法

优先分两步：

1. 先调优现有配置，不改调用方式
2. 再按任务类型拆分线程池

第一步建议：

- 根据机器核数和服务器规模调大 `airopscat.thread.blocking.*`
- 增加线程池队列长度、活跃线程数、拒绝执行次数日志

第二步建议：

- 部署类：`deploymentTaskExecutor`
- 监控采集类：`monitorTaskExecutor`
- 备份/恢复类：`backupTaskExecutor`

#### 收益

- 避免高峰期“部署任务压住监控采集”
- 避免备份/恢复抢占线上请求相关后台资源
- 对现有 Service 结构友好，只是替换 `@Named`

### 3.4 第一优先级：控制定时任务并发与批量大小

#### 建议

给定时任务增加明确的并发策略、分批处理和运行保护，避免任务重叠。

#### 当前信号

[ProgrammaticTaskManager](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/scheduler/ProgrammaticTaskManager.java) 已支持 `Scheduled.ConcurrentExecution.SKIP`，但不是所有任务都开启了跳过并发。

#### 适合当前项目的做法

- 为监控、流量统计、资源提醒类任务统一评估是否使用 `SKIP`
- 将大批量对象按固定批次处理，例如每批 20 台服务器或 100 个账号
- 对单次执行耗时超过阈值的任务打告警日志

#### 收益

- 避免定时任务堆积
- 降低数据库和 SSH 突刺
- 提升后台稳定性

### 3.5 第二优先级：优化搜索条件，减少索引失效

#### 建议

对高频搜索页做“精准匹配优先、模糊匹配兜底”的两段式搜索。

#### 适合当前项目的做法

以节点列表为例：

- `serverId`、`type`、`coreType`、`protocol` 保持精准过滤
- 文本搜索优先命中 `name`、`ip`、`host` 的原值或前缀匹配
- 对确实需要忽略大小写的字段，可以增加规范化列，例如小写副本列，再对副本列建索引

#### 收益

- 列表页在数据量变大后不容易退化
- 不需要推翻现有 Panache 写法

### 3.6 第二优先级：缓存低频变化、读取频繁的数据

#### 建议

对变化不频繁但读取非常频繁的数据做内存缓存。

#### 优先对象

- 控制台菜单结构 `menuGroups`
- 枚举选项、下拉字典
- 系统配置中读取非常频繁的固定值
- 首页统计卡片

#### 适合当前项目的做法

- 优先在现有 Service 内做简单缓存
- 先使用 `volatile + 定时刷新` 或基于更新时间的懒刷新
- 后续如需要再引入 Quarkus Cache

#### 收益

- 降低重复组装与重复查询
- 改动小，适合快速上线

### 3.7 第二优先级：减少 Qute 页面双次渲染成本

#### 建议

将“内容模板先渲染成字符串再灌入布局”的方式，逐步优化为更轻的组合方式。

#### 适合当前项目的做法

- 短期：先缓存 `menuGroups`、页面元数据等布局公共数据
- 中期：考虑将布局页与内容页改成 Qute 模板片段组合，避免中间 `String`
- 保持现有页面结构与模板路径不变，降低前端模板改动范围

#### 收益

- 降低控制台页面渲染 CPU 消耗
- 对首屏和高并发访问更友好

### 3.8 第二优先级：优化 SSH / gRPC / 流量采集链路

#### 当前信号

[SingBoxTrafficStatsCollector](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/traffic/impl/SingBoxTrafficStatsCollector.java) 每次采集都包含：

- 建立本地端口转发
- 发起 gRPC 查询
- 取消端口转发

#### 建议

- 同一轮任务内尽量复用已建立的 SSH 连接与转发
- 对单台服务器的多个采集项合并执行
- 给采集链路增加超时、失败熔断和重试上限

#### 收益

- 降低 SSH 连接抖动和端口转发开销
- 提升流量采集任务的整体吞吐

### 3.9 第二优先级：前端静态资源按需加载

#### 建议

让重资源只在需要的页面加载，不要在布局页全局兜底。

#### 优先处理项

- 图表库按页面加载
- `tom-select` 仅在有高级筛选/下拉增强页面加载
- 外部字体改为本地托管或允许关闭
- 为 `/static/*` 配置更积极的缓存策略和版本号

#### 收益

- 改善首屏时间
- 降低后台控制台切页卡顿

### 3.10 第三优先级：生产环境日志降噪

#### 当前信号

[application.properties](/Users/xiong/code/me/AirOpsCat/src/main/resources/application.properties) 中：

- `quarkus.log.level=INFO`
- `quarkus.log.category."com.fun90.airopscat".level=DEBUG`

#### 建议

- 将业务包 DEBUG 调整为 `%dev` 生效
- 生产默认回到 INFO
- 对大批量任务只保留摘要日志

#### 收益

- 减少 I/O 和字符串拼装开销
- 提高排障日志信噪比

## 4. MySQL 专项优化建议

这一部分单独展开数据库侧优化，因为它在 AirOpsCat 里收益会非常直接。

### 4.1 连接池与 JDBC 参数优化

#### 当前现状

[application.properties](/Users/xiong/code/me/AirOpsCat/src/main/resources/application.properties) 当前配置为：

- `quarkus.datasource.jdbc.min-size=2`
- `quarkus.datasource.jdbc.max-size=10`
- `quarkus.datasource.jdbc.acquisition-timeout=30s`
- JDBC URL 仅包含字符集、时区、SSL 等基础参数

#### 建议

1. 根据部署规模调整连接池大小

- 小规模单机：`min-size=5`，`max-size=20`
- 中等规模：`min-size=8`，`max-size=30`
- 如果监控、统计、列表页请求都较频繁，`max-size=10` 很容易偏紧

2. 为 MySQL 驱动补充 Prepared Statement 与批处理友好参数

可评估加入：

- `cachePrepStmts=true`
- `prepStmtCacheSize=250`
- `prepStmtCacheSqlLimit=2048`
- `useServerPrepStmts=true`
- `rewriteBatchedStatements=true`
- `maintainTimeStats=false`

建议方向如下：

```properties
quarkus.datasource.jdbc.url=jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:airopscat}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true&rewriteBatchedStatements=true&maintainTimeStats=false
```

#### 收益

- 降低高频 SQL 的解析与编译成本
- 提高插入/更新型统计任务吞吐
- 缓解连接池紧张导致的等待

### 4.2 索引要按查询形态补

#### 当前信号

- [AccountOnlineIpRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/AccountOnlineIpRepository.java) 高频使用 `accountNo + lastOnlineTime`、`nodeIp + lastOnlineTime`、`lastOnlineTime`
- [AccountTrafficStatsRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/AccountTrafficStatsRepository.java) 高频使用 `accountId + periodStart + periodEnd`
- [ServerTrafficStatsRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/ServerTrafficStatsRepository.java) 高频使用 `serverId + periodStart + periodEnd` 和 `serverId order by periodEnd desc`

#### 更具体的索引建议

`account_online_ip`

- 唯一键已存在：`(account_no, client_ip, node_ip)`
- 建议新增：`(account_no, last_online_time)`
- 建议新增：`(node_ip, last_online_time)`
- 建议新增：`(last_online_time)`

`account_traffic_stats`

- 建议新增：`(account_id, period_start, period_end)`
- 建议新增：`(user_id, period_start, period_end)`，如果用户维度统计较多

`server_traffic_stats`

- 建议新增：`(server_id, period_start, period_end)`
- 建议新增：`(server_id, period_end)`

`node`

- 建议新增：`(server_id, port)`，直接覆盖端口占用校验
- 建议新增：`(deployed, id)`，覆盖待部署节点扫描
- 建议新增：`(node_group)`
- 建议新增：`(type, core_type)`
- 建议新增：`(server_id)`、`(access_host_id)`、`(out_id)`

`server`

- 建议新增：`(disabled, external, expire_date)`
- 建议新增：`(supplier)`
- 建议新增：`(expire_date)`

#### 实施原则

- 先抓慢 SQL，再精确落索引
- 避免“把所有字段都建单列索引”
- 优先复合索引，尽量覆盖 `where + order by`

### 4.3 优化 SQL 形态，减少索引失效

#### 当前问题

仓库里不少查询使用：

- `lower(...)`
- `trim(...)`
- `%keyword%`
- 子查询嵌套

这几类写法在 MySQL 里很容易让索引失效。

#### 建议

1. 避免在热点过滤列上直接做函数运算

例如当前类似：

- `lower(trim(coreType)) = ?`

更适合改为：

- 入库时统一规范化 `coreType`
- 查询时直接用规范化后的值精确匹配

2. 把“任意位置模糊搜索”改成“前缀匹配优先”

3. 对复杂搜索改成“两段式”

- 第一段：命中索引的精准/前缀搜索
- 第二段：再走兜底模糊搜索

4. 用 join 替代部分相关子查询

尤其是列表查询中频繁出现的：

- `serverId in (select ...)`
- `accessHostId in (select ...)`

### 4.4 控制历史表膨胀

#### 高风险表

- `account_online_ip`
- `account_traffic_stats`
- `server_traffic_stats`
- `server_monitor_stats`
- `node_deployment_history`

#### 建议

1. 明确保留周期

- 在线 IP：只保留最近 7 到 30 天
- 监控采样：保留 7 到 30 天明细
- 流量统计：保留明细后定期聚合成日表或月表
- 部署历史：保留最近 N 个版本或最近 N 天

2. 对清理任务做分批删除

- 每批删除固定数量，例如 1000 或 5000 行
- 循环执行直到删完

#### 收益

- 避免大事务、长锁和 undo 膨胀
- 让热点索引规模保持可控

### 4.5 统计查询优先预聚合

#### 当前信号

[AccountTrafficStatsRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/AccountTrafficStatsRepository.java) 和 [ServerRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/ServerRepository.java) 中存在较多：

- `sum(...)`
- `count(...)`
- `group by ...`

#### 建议

- 对首页卡片、列表统计卡片、供应商汇总等场景，增加小时级或天级汇总表
- 由定时任务写入汇总表，页面只查汇总表
- 保留现有 Repository 方法作为兜底，不一次性替换全部逻辑

### 4.6 用 EXPLAIN 和慢 SQL 驱动优化

#### 建议动作

1. 在 MySQL 开启慢 SQL 日志

- 可以先从 `1s` 阈值开始

2. 对以下查询做 `EXPLAIN`

- 节点列表
- 服务器列表
- 在线 IP 查询与清理
- 流量统计查询
- 过期扫描与提醒查询

3. 重点关注

- `type=ALL`
- `Using temporary`
- `Using filesort`
- 扫描行数过大
- 复合索引未命中左前缀

### 4.7 数据库实例侧建议

这部分需要结合部署机器资源确认，但方向上值得优先关注：

- 确保 `innodb_buffer_pool_size` 足够大
- 让 `max_connections` 与应用连接池总和对齐
- 打开 `performance_schema`
- 确保表都使用 InnoDB 和 `utf8mb4`

### 4.8 MySQL 优化的建议顺序

1. 开慢 SQL 日志并抓热点 SQL
2. 为 `node`、`account_online_ip`、`account_traffic_stats`、`server_traffic_stats`、`server` 补复合索引
3. 调整 JDBC URL 与连接池参数
4. 把热点统计改成预聚合
5. 对历史表做批量清理与归档

## 5. 分阶段优化计划

## 5.1 高收益优化项分步处理

这一节专门把最值得先做的优化拆成可执行步骤，适合直接拿来排期。

### Step 1：先抓慢 SQL 和接口耗时基线

#### 要做什么

- 开启 MySQL 慢 SQL 日志
- 统计核心接口响应时间
- 对节点列表、服务器列表、流量统计、在线 IP 查询执行 `EXPLAIN`

#### 为什么先做

- 这是后续索引和 SQL 优化的依据
- 可以避免“拍脑袋加索引”

#### 预期收益

- 快速识别最值得先优化的热点 SQL
- 让后续优化更容易评估效果

### Step 2：优先减少列表页重复查询 [已处理]

#### 要做什么

- 分页接口与统计接口彻底分离
- 统计数据改成首屏单独请求
- 保持现有 `stats` 方法复用，不重写统计逻辑

#### 为什么这一项优先级高

- 改动集中在 Controller 层
- 对现有业务逻辑侵入很小
- 对节点、服务器、账户、域名等多个列表页都能立即见效

#### 预期收益

- 单次列表请求 SQL 数下降
- 翻页、搜索、排序时响应更稳定

#### 已处理内容

- 已为节点、服务器、账户、用户、标签、交易、路由规则、服务器配置、DNS 提供商配置、域名列表接口完成分页与统计分离
- 服务器列表的在线账号统计已改为按当前页服务器 IP 批量聚合，不再拉全量在线记录再内存分组
- 前端高频列表页已改为分页请求只拉列表数据
- 前端列表页已接入独立 `/stats` 请求，首屏加载与写操作后刷新统计
- 分页接口中的统计字段已移除，统一复用独立 `/stats` 接口

### Step 3：补最核心的 MySQL 复合索引 [已处理]

#### 要做什么

- 优先补 `node`
- 优先补 `server`
- 优先补 `account_online_ip`
- 优先补 `account_traffic_stats`
- 优先补 `server_traffic_stats`

#### 推荐先补的索引

- `node(server_id, port)`
- `node(deployed, id)`
- `node(type, core_type)`
- `server(disabled, external, expire_date)`
- `account_online_ip(account_no, last_online_time)`
- `account_online_ip(node_ip, last_online_time)`
- `account_traffic_stats(account_id, period_start, period_end)`
- `server_traffic_stats(server_id, period_start, period_end)`
- `server_traffic_stats(server_id, period_end)`

#### 为什么这一项优先级高

- 直接影响列表页、清理任务、统计任务、部署扫描
- 通常是最直接的数据库收益来源

#### 预期收益

- 明显减少全表扫描
- 慢 SQL 数量下降

#### 已处理内容

- 已在实体层补充第一批 JPA 索引声明
- 已覆盖 `node`、`server`、`account_online_ip`、`account_traffic_stats`、`server_traffic_stats`
- 后续仍需补生产环境 DDL，并结合 `EXPLAIN` 继续微调

### Step 4：优化 MySQL 连接池和 JDBC 参数

#### 要做什么

- 调整 `quarkus.datasource.jdbc.min-size`
- 调整 `quarkus.datasource.jdbc.max-size`
- 在 JDBC URL 中加入 Prepared Statement 缓存和批处理友好参数

#### 为什么这一项现在做

- 索引补完后，连接复用和 SQL 执行成本会成为下一层瓶颈
- 改动成本低，配置可灰度验证

#### 预期收益

- 高并发下数据库等待降低
- 统计写入和批量更新更稳定

### Step 5：控制定时任务和阻塞线程池竞争

#### 要做什么

- 调整 `airopscat.thread.blocking.*`
- 为监控、采集、提醒任务评估并开启 `SKIP`
- 对大批量任务引入分批处理

#### 为什么这一项优先级高

- 这是后端“越跑越慢”的常见来源
- 当前部署、监控、备份共用阻塞线程池，容易互相挤占

#### 预期收益

- 避免后台任务高峰影响前台请求
- 调度稳定性明显提升

### Step 6：重构热点搜索 SQL [已处理]

#### 要做什么

- 减少 `lower(...)`、`trim(...)`
- 减少 `%keyword%`
- 把部分子查询改成 join
- 对高频字段尽量改成规范化存储后精确匹配

#### 为什么放在索引之后

- 需要基于实际慢 SQL 和 `EXPLAIN` 结果动手
- 改动比单纯补索引更大，适合第二波处理

#### 预期收益

- 数据量增长后列表页退化速度明显减缓

### Step 7：给统计与历史表做预聚合和归档 [部分处理]

#### 要做什么

- 对监控明细、流量明细设保留周期
- 对历史表做分批清理
- 对首页卡片和统计页逐步改成查汇总表

#### 为什么放在后面

- 这是结构性收益最大的优化之一
- 但会涉及表设计、调度策略和页面查询切换，适合第二阶段推进

#### 预期收益

- 大表膨胀得到控制
- 统计页不再直接压明细表

### Step 8：再处理模板和前端静态资源 [部分处理]

#### 要做什么

- 优化 Qute 双次渲染
- 缓存菜单和公共布局数据
- 将图表、增强控件改成按需加载

#### 为什么放在最后

- 这类优化能提升体验，但通常不如数据库和后台任务收益直接
- 更适合在后端热点处理完后统一做体验收口

#### 预期收益

- 控制台首屏和切页体验更平滑

## 5.2 开发任务清单

这一节把上面的 8 个步骤继续细化到开发可执行层面，便于直接排期。

### Task Group 1：慢 SQL 与基线采集

#### 涉及内容

- MySQL 慢 SQL 日志
- 接口耗时日志
- 关键查询 `EXPLAIN`

#### 建议动作

1. 在 MySQL 打开慢 SQL 日志，先使用 `1s` 阈值
2. 记录以下接口的平均耗时和 P95
3. 对热点 SQL 输出 `EXPLAIN` 结果并留档

#### 优先观察的接口

- `/api/admin/nodes`
- `/api/admin/servers`
- `/api/admin/accounts`
- `/api/admin/traffic-stats`
- `/api/admin/backups`

#### 产出物

- 慢 SQL 清单
- 热点接口耗时基线表
- `EXPLAIN` 分析记录

#### 验收标准

- 至少定位出 Top 10 慢 SQL
- 至少覆盖 5 个高频接口

### Task Group 2：列表页重复查询治理 [已处理]

#### 涉及类

- [NodeController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/NodeController.java)
- [ServerController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/ServerController.java)
- [AccountController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/AccountController.java)
- 其他带 `records + total + stats` 返回结构的 Controller

#### 建议动作

1. 分页接口只保留分页数据返回，统计统一走独立 `/stats` 接口
2. 将现有统计逻辑保留在 Service 层，不复制代码
3. 前端页面首屏单独拉取统计接口
4. 翻页、排序、搜索时只刷新列表，不重复刷新统计

#### 复用建议

- 继续复用现有 `getNodesStats()`、`getServerStats()`、`getAccountStats()` 风格方法
- 不新建重复 Repository 查询，先沿用现有统计方法

#### 验收标准

- 列表接口 SQL 次数明显下降
- 前端交互无功能回归

#### 当前进度

- 已处理：
- [NodeController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/NodeController.java) 已移除分页接口统计字段，统计改走 `/api/admin/nodes/stats`
- [ServerController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/ServerController.java) 已移除分页接口统计字段，统计改走 `/api/admin/servers/stats`
- [AccountController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/AccountController.java) 已移除分页接口统计字段，统计改走 `/api/admin/accounts/stats`
- [UserController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/UserController.java) 已移除分页接口统计字段，统计改走 `/api/admin/users/stats`
- [TagController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/TagController.java) 已移除分页接口统计字段，统计改走 `/api/admin/tags/stats`
- [TransactionController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/TransactionController.java) 已移除分页接口统计字段，统计改走 `/api/admin/transactions/stats`
- [RouteRuleController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/RouteRuleController.java) 已移除分页接口统计字段，统计改走 `/api/admin/route-rules/stats`
- [ServerConfigController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/ServerConfigController.java) 已移除分页接口统计字段，统计改走 `/api/admin/server-configs/stats`
- [DnsProviderConfigController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/DnsProviderConfigController.java) 已移除分页接口统计字段，统计改走 `/api/admin/dns-provider-configs/stats`
- [DomainController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/DomainController.java) 已移除分页接口统计字段，统计改走 `/api/admin/domains/stats`
- [data-table.js](/Users/xiong/code/me/AirOpsCat/src/main/resources/META-INF/resources/static/js/common/data-table.js) 已支持列表/统计分离请求
- 用户、标签、账户、节点、服务器、域名、交易、路由规则、服务器配置、DNS 提供商页面已完成前端联调
- 前后端已统一为分页接口不返回统计，统计由独立 `/stats` 接口承担

### Task Group 3：MySQL 索引补齐 [已处理]

#### 涉及实体

- [Node](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/Node.java)
- [Server](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/Server.java)
- [AccountOnlineIp](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/AccountOnlineIp.java)
- [AccountTrafficStats](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/AccountTrafficStats.java)
- [ServerTrafficStats](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/ServerTrafficStats.java)

#### 建议动作

1. 先通过 `@Table(indexes = ...)` 补 JPA 索引声明
2. 同步整理一份生产环境 DDL 脚本
3. 上线前用 `EXPLAIN` 复核索引命中情况

#### 第一批推荐索引

- `idx_node_server_port(server_id, port)`
- `idx_node_deployed_id(deployed, id)`
- `idx_node_type_core(type, core_type)`
- `idx_server_state_expire(disabled, external, expire_date)`
- `idx_account_online_account_time(account_no, last_online_time)`
- `idx_account_online_node_time(node_ip, last_online_time)`
- `idx_account_traffic_account_period(account_id, period_start, period_end)`
- `idx_server_traffic_server_period(server_id, period_start, period_end)`
- `idx_server_traffic_server_end(server_id, period_end)`

#### 验收标准

- 关键慢 SQL 不再出现明显全表扫描
- 热点 SQL 的扫描行数明显下降

#### 当前进度

- 已处理：
- [Node](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/Node.java) 已增加第一批索引
- [Server](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/Server.java) 已增加第一批索引
- [AccountOnlineIp](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/AccountOnlineIp.java) 已增加第一批索引
- [AccountTrafficStats](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/AccountTrafficStats.java) 已增加第一批索引
- [ServerTrafficStats](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/ServerTrafficStats.java) 已增加第一批索引
- 待处理：
- 补生产环境 DDL
- 结合慢 SQL / `EXPLAIN` 继续复核

### Task Group 4：连接池与 JDBC 参数优化

#### 涉及配置

- [application.properties](/Users/xiong/code/me/AirOpsCat/src/main/resources/application.properties)
- [application-dev.properties](/Users/xiong/code/me/AirOpsCat/src/main/resources/application-dev.properties)

#### 建议动作

1. 调整连接池大小
2. 为 JDBC URL 增加 Prepared Statement 缓存参数
3. 增加批处理友好参数
4. 与 MySQL `max_connections` 对齐确认

#### 推荐先试配置

```properties
quarkus.datasource.jdbc.min-size=5
quarkus.datasource.jdbc.max-size=20
quarkus.datasource.jdbc.acquisition-timeout=15s
```

JDBC URL 可评估补充：

- `cachePrepStmts=true`
- `prepStmtCacheSize=250`
- `prepStmtCacheSqlLimit=2048`
- `useServerPrepStmts=true`
- `rewriteBatchedStatements=true`

#### 验收标准

- 连接等待时间下降
- 高峰期无明显连接池耗尽

### Task Group 4.1：服务器列表在线账号统计优化 [已处理]

#### 当前进度

- [AccountOnlineIpRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/AccountOnlineIpRepository.java) 已增加按节点 IP + 时间窗口批量统计在线数查询
- [AccountOnlineIpService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/AccountOnlineIpService.java) 已将按节点 IP 查询在线记录改为直接走时间窗口 SQL
- [ServerController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/ServerController.java) 已改为按当前页服务器 IP 批量聚合在线账号数，不再拉全量在线记录后在内存中分组

#### 收益

- 降低服务器列表页的数据库扫描范围和内存占用
- 避免在线记录增大后列表页跟着线性变慢

### Task Group 5：调度任务与阻塞线程池治理

#### 涉及类

- [BlockingTaskExecutorConfig](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/config/BlockingTaskExecutorConfig.java)
- [ProgrammaticTaskManager](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/scheduler/ProgrammaticTaskManager.java)
- [ServerMonitorTask](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/scheduler/ServerMonitorTask.java)
- [NodeDeploymentService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java)
- [DatabaseBackupService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/DatabaseBackupService.java)

#### 建议动作

1. 先调大 `airopscat.thread.blocking.*`
2. 给线程池增加运行指标日志
3. 为高风险定时任务评估 `Scheduled.ConcurrentExecution.SKIP`
4. 大批量任务增加分批处理
5. 第二阶段再拆分 `deploymentTaskExecutor`、`monitorTaskExecutor`、`backupTaskExecutor`

#### 验收标准

- 队列堆积显著减少
- 后台任务互相阻塞情况下降

### Task Group 6：热点搜索 SQL 重构 [已处理]

#### 涉及类

- [NodeService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/NodeService.java)
- [ServerService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/ServerService.java)
- [AccountService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/AccountService.java)

#### 建议动作

1. 将高频精确过滤字段优先前置
2. 减少 `lower(...)`、`trim(...)`
3. 将部分 `%keyword%` 改成前缀匹配
4. 将部分子查询重写成 join

#### 复用建议

- 保持现有 Panache 查询风格
- 先在原 Service 方法里迭代，不额外复制一套查询实现

#### 验收标准

- 热点列表页 `EXPLAIN` 更稳定
- 搜索性能提升且功能不回退

#### 已处理内容

- [NodeService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/NodeService.java) 已将节点搜索改为“节点字段优先 + 预查服务器/接入 Host ID”模式，减少 `lower(...)` 与关联子查询扩散
- [ServerService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/ServerService.java) 已将服务器搜索改为“精确/前缀优先，必要字段 contains 兜底”，并将 `ServerHost` 搜索收敛为预查服务器 ID
- [AccountService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/AccountService.java) 已将账户搜索改为账号/UUID 精确或前缀优先，并通过预查用户 ID 代替 `user.email`、`user.nickName` 的列函数模糊查询
- [ServerRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/ServerRepository.java)、[ServerHostRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/ServerHostRepository.java)、[UserRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/UserRepository.java) 已补充关键词预查辅助方法，用于收敛主查询范围

### Task Group 7：统计预聚合与历史归档 [部分处理]

#### 涉及范围

- 流量统计
- 监控统计
- 在线 IP 历史
- 部署历史

#### 建议动作

1. 明确保留周期
2. 对清理任务改成分批删除
3. 增加日级或小时级汇总表
4. 首页和统计页逐步改查汇总表

#### 适合当前项目的推进方式

- 先保留现有明细表逻辑
- 新增聚合任务和汇总查询
- 页面逐步切换，不一次性替换全部接口

#### 验收标准

- 历史表增长速度可控
- 统计页对明细表压力下降

#### 已处理内容

- [AccountOnlineIpRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/AccountOnlineIpRepository.java) 已将在线记录清理改为 MySQL `LIMIT` 批量删除
- [AccountOnlineIpService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/AccountOnlineIpService.java) 已改为循环批处理清理，并移除 SQLite 遗留的 `database is locked` 判断
- [ServerMonitorStatsRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/ServerMonitorStatsRepository.java) 已增加监控历史批量删除方法
- [ServerMonitorStatsService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/ServerMonitorStatsService.java) 已改为按批循环清理监控历史，并输出批次数和删除总数
- [SystemConfigService.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/SystemConfigService.java) 已补充在线记录和监控历史清理批大小配置
- 待继续处理：
- 统计预聚合汇总表
- 首页和统计页逐步切换到汇总查询

### Task Group 8：Qute 与前端资源收口优化

#### 涉及文件

- [HomeController](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/controller/HomeController.java)
- [layout.html](/Users/xiong/code/me/AirOpsCat/src/main/resources/templates/layout.html)
- `src/main/resources/META-INF/resources/static/js/*`

#### 建议动作

1. 先缓存菜单和布局公共数据
2. 再优化双次渲染结构
3. 将图表库和增强控件改成按需加载
4. 为静态资源增加版本和缓存策略

#### 验收标准

- 页面首屏和切页更流畅
- 静态资源加载体积下降

#### 当前进度

- 已处理：
- [ConsolePageRegistry.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/service/ConsolePageRegistry.java) 已将控制台菜单分组改为启动时预计算并复用，不再按请求重复组装
- [ConsolePage.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/model/vo/ConsolePage.java) 已补充页面级可选资源标记，用于布局按需加载
- [HomeController.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/controller/HomeController.java) 已向布局模板传递 `requiresTomSelect`、`requiresCharts` 页面元数据
- [layout.html](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/resources/templates/layout.html) 已改为按需加载 `tom-select` 的 CSS/JS，并移除全局远程 `Inter` 字体请求
- [HomeController.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/controller/HomeController.java) 已移除 `RawString` 拼接与内容模板预渲染，控制台页面改为单次 Qute 渲染
- 控制台已注册页面已切换到 `page.html` 包装模板，通过布局插槽 include 原有 `content.html`
- 待继续处理：
- 图表资源、增强控件的更细粒度按页或按模块收口
- 静态资源版本号与缓存策略配置

## 5.3 建议排期

### 第 1 周

- 慢 SQL 采集
- 列表页重复查询治理
- 第一批索引方案确认

### 第 2 周

- 索引上线
- JDBC 与连接池调优
- 调度任务并发和线程池调优

### 第 3 到 4 周

- 热点搜索 SQL 重构
- 统计预聚合方案落地
- 历史表清理策略落地

### 第 5 周以后

- Qute 渲染优化
- 前端静态资源按需加载
- 更长期的数据归档与报表分层

## Phase 1：两周内完成的高收益低风险项

### 目标

在不改架构的前提下，先降低 SQL 次数和后台阻塞竞争。

### 任务

1. 分页接口与统计接口完全分离，前端统一在首屏和写操作后单独拉 `/stats`
2. 为 `node`、`server`、`account_online_ip`、`account_traffic_stats`、`server_traffic_stats` 补核心索引
3. 调整 MySQL JDBC URL 和连接池参数
4. 调整 `airopscat.thread.blocking.*` 默认值，并增加线程池运行日志
5. 为监控、流量采集、通知类任务评估并开启 `SKIP`
6. 将生产日志等级调整为更保守的配置

### 预期收益

- 列表接口 SQL 次数明显下降
- 翻页和搜索响应时间下降
- 定时任务峰值时段更稳定

### 验收指标

- 主要列表页平均响应时间下降 20% 以上
- 线程池队列持续堆积情况明显减少
- 慢 SQL 数量下降

## Phase 2：一个月内完成的结构优化项

### 目标

提升中高数据量下的稳定性，避免随着节点/服务器数增长而线性恶化。

### 任务

1. 对热点搜索页做搜索条件重构，减少 `lower(...) like` 依赖
2. 对菜单、字典、统计卡片增加轻量缓存
3. 将阻塞线程池按任务类型拆分
4. 对监控和流量采集任务做批处理与限流
5. 为流量和监控统计增加预聚合方案
6. 优化 Qute 公共布局数据装配

### 预期收益

- 高峰时后台稳定性更强
- 调度任务对在线请求影响减小
- 页面渲染耗时进一步下降

## Phase 3：按需推进的长期优化项

### 目标

处理体量继续增长后的结构性瓶颈。

### 任务

1. 重构布局模板组合方式，减少双次渲染
2. 统一 SSH / gRPC 采集连接复用策略
3. 完善静态资源按需加载与缓存版本化
4. 为历史大表建立归档与分层存储策略
5. 为核心链路补充性能基线和回归压测

### 预期收益

- 控制台体验更平滑
- 后台调度吞吐更稳定
- 后续新模块接入性能风险更低

## 5. 推荐的实施顺序

如果只做最值钱的几项，建议顺序如下：

1. 列表页去掉重复统计查询
2. 给热点表补索引
3. 调优并拆分阻塞线程池
4. 给定时任务加并发保护和批处理
5. 优化搜索条件和缓存
6. 处理模板渲染和前端静态资源

## 6. 风险与注意事项

- 索引新增会影响写入性能，需要先看表数据量和慢 SQL 再精确落表
- 连接池放大后要同步确认 MySQL `max_connections`，否则只是把等待从应用侧转移到数据库侧
- 搜索条件调整可能改变“模糊搜索命中范围”，上线前要做功能回归
- 线程池拆分后要观察是否出现新的资源闲置或局部拥塞
- Qute 模板改造虽然收益明确，但涉及公共布局，建议放在第二阶段之后
- 涉及新增 DTO、缓存对象、页面参数时，仍需按仓库约束检查是否需要同步更新 `JsonReflectionConfiguration`

## 6. 本轮实施更新

### Task Group 4：连接池与 JDBC 参数优化 [已处理]

- [application.properties](/Users/xiong/code/me/AirOpsCat/src/main/resources/application.properties) 已将 `quarkus.datasource.jdbc.min-size` 从 `2` 调整为 `5`
- [application.properties](/Users/xiong/code/me/AirOpsCat/src/main/resources/application.properties) 已将 `quarkus.datasource.jdbc.max-size` 从 `10` 调整为 `20`
- [application.properties](/Users/xiong/code/me/AirOpsCat/src/main/resources/application.properties) 已将 `quarkus.datasource.jdbc.acquisition-timeout` 从 `30s` 调整为 `15s`
- [application.properties](/Users/xiong/code/me/AirOpsCat/src/main/resources/application.properties) 已补充 `cachePrepStmts`、`prepStmtCacheSize`、`prepStmtCacheSqlLimit`、`useServerPrepStmts`、`rewriteBatchedStatements`、`maintainTimeStats`
- [SystemConfigService.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/SystemConfigService.java) 已同步补充线程池默认值与说明
- 待继续复核：
- 生产环境 `max_connections`
- 慢 SQL 与连接池等待时间

### Task Group 5：调度任务与阻塞线程池治理 [已处理]

- [BlockingTaskExecutorConfig.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/config/BlockingTaskExecutorConfig.java) 已将线程池默认值调整为 `core-size=4`、`max-size=16`、`queue-capacity=128`、`keep-alive-seconds=60`
- [BlockingTaskExecutorConfig.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/config/BlockingTaskExecutorConfig.java) 已补充线程池运行态摘要方法，供后台任务统一输出日志
- [ProgrammaticTaskManager.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/scheduler/ProgrammaticTaskManager.java) 已为 `backup-create`、`backup-cleanup`、`server-traffic-notify`、`account-expiration`、`resource-expiration-notify`、`traffic-stats-collect`、`core-config-cleanup` 补齐 `Scheduled.ConcurrentExecution.SKIP`
- [ServerMonitorTask.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/scheduler/ServerMonitorTask.java) 已增加监控采集前后线程池摘要日志
- [DatabaseBackupService.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/DatabaseBackupService.java) 已增加备份与清理任务的线程池摘要日志
- [BlockingTaskExecutorConfig.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/config/BlockingTaskExecutorConfig.java) 已新增 `deploymentTaskExecutor`、`monitorTaskExecutor`、`backupTaskExecutor`，并为不同线程池使用独立线程名前缀
- [NodeDeploymentService.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java) 已切换到 `deploymentTaskExecutor`，并支持按服务器批次限流部署
- [ServerMonitorTask.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/scheduler/ServerMonitorTask.java) 已切换到 `monitorTaskExecutor`，并按批处理监控采集
- [ServerMonitorLoadNotifier.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/expiration/ServerMonitorLoadNotifier.java) 已切换到 `monitorTaskExecutor`，并复用监控并发上限配置
- [DatabaseBackupService.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/DatabaseBackupService.java) 已切换到 `backupTaskExecutor`
- [SystemConfigService.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/SystemConfigService.java) 已补充部署/监控/备份线程池参数与并发服务器数配置
- 本轮未处理：
- 更细粒度的任务配额与动态限流
- 任务执行耗时周报

### Task Group 6：热点搜索 SQL 重构 [已处理]

- [NodeService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/NodeService.java) 已将节点列表搜索改为精确/前缀优先，并通过预查 `serverId`、`accessHostId` 缩小主查询范围
- [ServerService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/ServerService.java) 已将服务器列表搜索改为精确/前缀优先，并通过预查 `ServerHost` 命中的服务器 ID 代替关联模糊子查询
- [AccountService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/AccountService.java) 已将账户列表搜索改为账号/UUID 精确或前缀优先，并预查匹配用户 ID
- [ServerRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/ServerRepository.java)、[ServerHostRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/ServerHostRepository.java)、[UserRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/UserRepository.java) 已补充关键词搜索预查方法，减少主查询里的 `lower(...)` 和扩散子查询

### Task Group 7：统计预聚合与历史归档 [部分处理]

- [AccountOnlineIpRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/AccountOnlineIpRepository.java) 已改为按批删除过期在线记录
- [AccountOnlineIpService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/AccountOnlineIpService.java) 已输出在线记录批处理清理日志，并移除 SQLite 遗留异常文案
- [ServerMonitorStatsRepository](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/repository/ServerMonitorStatsRepository.java) 已增加按批删除监控历史记录能力
- [ServerMonitorStatsService](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/ServerMonitorStatsService.java) 已改为监控历史批处理清理
- [ProgrammaticTaskManager.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/scheduler/ProgrammaticTaskManager.java) 已为 `server-monitor-cleanup` 补齐 `Scheduled.ConcurrentExecution.SKIP`
- [SystemConfigService.java](/Users/xiong/code/me/AirOpsCat/src/main/java/com/fun90/airopscat/service/SystemConfigService.java) 已补充 `airopscat.account.online.cleanup.batch-size` 与 `airopscat.server.monitor.cleanup.batch-size`
- 本轮未处理：
- 预聚合汇总表设计与落地
- 页面查询切换到汇总表

### Task Group 8：Qute 与前端资源收口优化 [部分处理]

- [ConsolePageRegistry.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/service/ConsolePageRegistry.java) 已将 `menuGroups` 改为启动期预计算缓存，避免每次控制台请求重复执行分组和排序
- [ConsolePage.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/model/vo/ConsolePage.java) 已为页面增加 `requiresTomSelect`、`requiresCharts` 标记，支持布局按需决策资源加载
- [HomeController.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/controller/HomeController.java) 已将页面可选资源标记注入布局上下文
- [layout.html](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/resources/templates/layout.html) 已将 `tom-select` CSS/JS 改为按需加载，并移除全局远程字体依赖
- [HomeController.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/controller/HomeController.java) 已切换为单次渲染页面包装模板，不再先渲染 `content.html` 再注入布局
- `src/main/resources/templates/*/page.html` 已为控制台注册页面提供统一包装模板，布局通过 `page-body` 插槽直接 include 原有内容模板
- [page-content.html](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/resources/templates/page-content.html) 已移除，旧的 `pageContent` 注入链路已下线
- 本轮未处理：
- 更细粒度的图表资源按需收口
- 静态资源缓存策略与版本化

## 7. 建议先落地的文档化交付物

为了让优化推进更顺畅，建议紧接着补两份内部材料：

- 慢 SQL 与热点接口清单
- 定时任务执行耗时与失败率周报

这样可以把“感觉慢”变成“有数据支撑的优化”，后续优先级也更容易统一。
