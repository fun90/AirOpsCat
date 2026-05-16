## Context

系统当前已有 `/api/open/*` 与 `/subscribe/*` 公开入口，其中 `/api/open/docs-info/*` 面向文档/开放信息读取，`/subscribe/*` 面向订阅客户端抓取。这类请求目前没有结构化访问日志，排查异常 IP、路径访问热度、订阅抓取频率时只能依赖基础服务日志，无法在控制台按条件检索或统计。

项目现有后台使用 Quarkus REST、Hibernate ORM with Panache、Qute、Tabler、petite-vue，并已内置 `/static/js/apexcharts.js`。控制台页面统一由 `ConsolePageRegistry` 注册，定时任务统一接入 `ProgrammaticTaskManager`。

## Goals / Non-Goals

**Goals:**

- 只记录配置命中的系统请求路径，默认包含 `/api/open/docs-info/*` 与 `/subscribe/*`。
- 持久化请求日志，至少包含客户端 IP，并补充足够的排查字段。
- 提供后台列表搜索，支持访问时间段、请求路径、客户端 IP，其中路径与 IP 为模糊匹配。
- 提供 apexcharts 统计图表，至少包含按请求路径统计的图表。
- 通过定时任务清理 30 天以前的日志，避免表无限增长。

**Non-Goals:**

- 不记录请求体、响应体、订阅内容或 token 明文，避免敏感信息扩大化。
- 不做实时告警、限流或封禁策略。
- 不替代应用运行日志，也不覆盖所有后台管理接口。
- 不引入外部日志系统、消息队列或搜索引擎。

## Decisions

### 1. 使用响应过滤器记录请求

新增 `SystemRequestLogFilter`，基于 JAX-RS `ContainerRequestFilter` + `ContainerResponseFilter` 或等效 Quarkus 过滤机制，在请求进入时记录开始时间，在响应阶段判断路径是否命中并写入日志。

选择响应阶段写入的原因是可以拿到 HTTP 状态码与请求耗时，列表和图表更有诊断价值。若只在请求进入阶段记录，无法区分成功、失败和慢请求。

过滤器只记录配置路径命中的请求，不记录后台管理页面静态资源等噪声请求。匹配规则采用简单 glob 风格前缀匹配，`/api/open/docs-info/*` 可覆盖其下所有子路径，`/subscribe/*` 可覆盖所有订阅路径。

### 2. 客户端 IP 解析策略

客户端 IP 优先从可信代理头解析，顺序为 `X-Forwarded-For`、`X-Real-IP`、Quarkus/Vert.x 远端地址。`X-Forwarded-For` 取第一个非空 IP，并裁剪为合理长度。

这样能适配常见反向代理部署；同时本功能只做日志展示，不基于该 IP 做安全决策，因此不在本次引入可信代理白名单。

### 3. 日志数据模型

新增 `SystemRequestLog` 实体，建议字段包括：

- `id`
- `accessTime`
- `method`
- `requestPath`
- `queryString`
- `clientIp`
- `statusCode`
- `durationMillis`
- `userAgent`
- `referer`
- `createdAt`

索引建议覆盖 `accessTime`、`requestPath`、`clientIp`，满足保留清理、时间过滤、路径/IP 查询和统计。`queryString`、`userAgent`、`referer` 需要长度限制，超长时截断；不记录请求体或响应体。

### 4. 配置项

新增配置项：

- `airopscat.request-log.enabled=true`
- `airopscat.request-log.paths=/api/open/docs-info/*,/subscribe/*`
- `airopscat.request-log.retention-days=30`
- `airopscat.request-log.cleanup.cron=0 30 3 * * ?`

路径配置允许后续扩展需要记录的公开入口。保留天数默认 30 天，按用户要求作为默认行为；实现上仍读取配置，便于运维调整。

### 5. 管理 API 与统计 API

新增后台 API，建议放在 `/api/admin/system-request-logs`：

- `GET /api/admin/system-request-logs`：分页查询，参数包括 `startTime`、`endTime`、`requestPath`、`clientIp`、`page`、`size`。
- `GET /api/admin/system-request-logs/stats`：返回统计数据，复用同一组过滤条件，包含按请求路径统计、按时间趋势统计、按客户端 IP Top N 统计。

列表查询按 `accessTime desc` 排序。模糊匹配对 `requestPath` 与 `clientIp` 使用 `LIKE`，输入统一 trim 并转义 `%`、`_` 等通配符，避免用户输入意外改变匹配语义。

### 6. 控制台页面

新增 `/console/system/request-log` 页面并注册到 `ConsolePageRegistry` 的 system 分组，菜单名建议为“请求日志”或“系统请求日志”。模板路径使用 `system/request-log/page`，脚本路径为 `/static/js/system/request-log.js`。

页面包含：

- 搜索区：访问时间起止、请求路径、客户端 IP、查询/重置按钮。
- 图表区：按请求路径统计图、访问趋势图、客户端 IP Top N 图。
- 列表区：访问时间、方法、路径、客户端 IP、状态码、耗时、User-Agent、Referer，支持分页。

图表使用项目已内置的 apexcharts，不新增前端依赖。统计接口和列表接口共享筛选条件，保证图表与列表范围一致。

### 7. 清理任务

新增 `SystemRequestLogCleanupTask`，删除 `accessTime < now - retentionDays` 的日志，并注册到 `ProgrammaticTaskManager`，采用 `Scheduled.ConcurrentExecution.SKIP`。默认每天凌晨执行一次，保留近 30 天。

清理任务只按访问时间删除，不依赖创建时间，确保补录或延迟写入数据仍以实际访问时间为准。

## Risks / Trade-offs

- **同步写库增加公开接口延迟** -> 日志字段少、单表插入简单；实现时捕获写入异常并记录应用日志，不影响原请求响应。
- **高频订阅请求导致日志表增长快** -> 通过默认 30 天清理、时间索引和分页查询控制数据量。
- **代理头可能被伪造** -> 本功能仅做审计展示，不用于鉴权或安全决策；未来如需要安全策略再引入可信代理配置。
- **模糊搜索在大数据量下可能走不到索引** -> 首期数据保留周期较短，列表必须分页；后续可根据数据规模增加归一化字段或专用索引。

## Migration Plan

1. 新增 `system_request_log` 表，字段与索引随 Hibernate schema update 自动创建或由数据库迁移脚本补齐。
2. 发布新配置默认值，启动后自动开始记录默认路径。
3. 注册清理任务，首次执行时删除 30 天以前的历史日志。
4. 回滚时停止新代码即可；已创建日志表可保留，不影响旧版本运行。

## Open Questions

- 是否需要在页面上显示 query string：本设计建议展示但做长度限制，便于排查订阅客户端参数问题。
- 是否允许后台手动删除单条日志：本次不做，避免审计记录被误删，统一依赖定时清理。
