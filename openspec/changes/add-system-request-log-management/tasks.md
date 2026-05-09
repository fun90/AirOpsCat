## 1. 配置与数据模型

- [x] 1.1 在 `application.properties` 增加请求日志配置默认值：启用开关、记录路径、保留天数 30、清理 cron
- [x] 1.2 新增 `SystemRequestLog` 实体，包含访问时间、方法、路径、查询参数、客户端 IP、状态码、耗时、User-Agent、Referer、创建时间等字段
- [x] 1.3 为请求日志表补充按访问时间、请求路径、客户端 IP 查询所需的索引或迁移说明
- [x] 1.4 新增 `SystemRequestLogRepository`，提供分页查询、统计聚合、按访问时间删除过期日志的方法

## 2. 请求采集与写入

- [x] 2.1 新增请求日志配置读取组件，解析 `airopscat.request-log.paths` 并支持 `/path/*` 风格路径匹配
- [x] 2.2 新增 `SystemRequestLogFilter`，在请求开始时记录开始时间，在响应阶段判断是否命中记录范围
- [x] 2.3 实现客户端 IP 解析，优先读取 `X-Forwarded-For`、`X-Real-IP`，再回退到远端地址
- [x] 2.4 实现日志写入服务，保存命中请求的访问时间、方法、路径、查询参数、客户端 IP、状态码、耗时、User-Agent、Referer
- [x] 2.5 对 query string、User-Agent、Referer、客户端 IP 等字段做长度限制与空值处理，不记录请求体、响应体或订阅内容正文
- [x] 2.6 确认日志写入异常不会影响原请求响应，仅记录应用日志

## 3. 查询与统计 API

- [x] 3.1 新增请求日志列表 DTO、统计 DTO 与查询条件 DTO，并确定前端需要的字段格式
- [x] 3.2 新增 `SystemRequestLogService`，实现按访问时间段、请求路径、客户端 IP 的分页查询，其中路径与 IP 使用模糊匹配
- [x] 3.3 在模糊查询中 trim 输入并转义 `%`、`_` 等 LIKE 通配符，避免输入意外改变匹配语义
- [x] 3.4 实现统计查询：按请求路径统计、按时间趋势统计、客户端 IP Top N 统计
- [x] 3.5 新增后台 REST API：`GET /api/admin/system-request-logs` 与 `GET /api/admin/system-request-logs/stats`
- [x] 3.6 为新增 Controller 请求/响应 DTO 检查并更新 `JsonReflectionConfiguration`

## 4. 清理任务

- [x] 4.1 新增 `SystemRequestLogCleanupTask`，按 `accessTime < now - retentionDays` 删除过期日志
- [x] 4.2 将清理任务注册到 `ProgrammaticTaskManager`，使用 `Scheduled.ConcurrentExecution.SKIP`
- [x] 4.3 确认清理任务默认保留近 30 天日志，并能通过配置调整保留天数

## 5. 控制台页面

- [x] 5.1 在 `ConsolePageRegistry` 的 system 分组注册 `/system/request-log` 页面，菜单名为“请求日志”或“系统请求日志”
- [x] 5.2 创建 `src/main/resources/templates/system/request-log/` 模板目录，包含页面主体、搜索条件、图表区域、表格和分页片段
- [x] 5.3 创建 `src/main/resources/META-INF/resources/static/js/system/request-log.js`，实现 petite-vue 页面状态、搜索、重置、分页和接口调用
- [x] 5.4 使用已内置的 `/static/js/apexcharts.js` 绘制按请求路径统计图，并至少再绘制访问趋势图或客户端 IP Top N 图
- [x] 5.5 确认图表和列表使用相同筛选条件，筛选后同步刷新统计和列表
- [x] 5.6 按现有 Tabler 风格展示列表字段：访问时间、方法、路径、客户端 IP、状态码、耗时、User-Agent、Referer

## 6. 验证

- [x] 6.1 运行编译或测试命令，至少执行 `./mvnw -DskipTests compile` 或等效验证
- [ ] 6.2 手动访问 `/api/open/docs-info/*` 与 `/subscribe/*` 的示例路径，确认日志写入且包含客户端 IP
- [ ] 6.3 手动访问非配置路径，确认不会写入请求日志
- [ ] 6.4 手动验证日志列表按时间段、请求路径、客户端 IP 搜索与分页均正常
- [ ] 6.5 手动验证 apexcharts 图表正常渲染，且按请求路径统计图存在
- [ ] 6.6 手动执行或触发清理任务，确认 30 天以前日志被删除且近 30 天日志保留
