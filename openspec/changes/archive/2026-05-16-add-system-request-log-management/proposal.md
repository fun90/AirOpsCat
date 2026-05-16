## Why

当前系统的公开文档信息与订阅入口缺少可追踪的访问审计，运维人员无法按路径、客户端 IP 或时间快速排查异常访问、订阅抓取频率和开放接口使用情况。新增系统请求日志管理可以补齐关键公开入口的访问留痕，并提供可视化统计辅助运营判断。

## What Changes

- 新增可配置的系统请求日志记录能力，默认记录 `/api/open/docs-info/*` 与 `/subscribe/*` 路径的请求。
- 请求日志至少记录客户端 IP，并记录访问时间、请求方法、请求路径、查询参数、状态码、耗时、User-Agent、Referer 等排查字段。
- 新增系统请求日志列表与 REST API，支持按访问时间段、请求路径、客户端 IP 过滤，其中路径和 IP 条件采用模糊匹配。
- 新增请求日志统计图表，前端使用 apexcharts，必须包含按请求路径统计的图表，并提供额外维度辅助观察访问趋势。
- 新增定时清理任务，仅保留近 30 天请求日志。

## Capabilities

### New Capabilities

- `system-request-log-management`: 系统请求日志的采集配置、持久化、查询、统计图表与 30 天保留清理。

### Modified Capabilities

无。

## Impact

- **后端**：新增请求过滤/拦截逻辑、请求日志实体与 Repository、查询统计 Service、管理 API、定时清理任务。
- **配置**：新增可记录路径配置项，默认包含 `/api/open/docs-info/*` 与 `/subscribe/*`；新增保留天数配置默认 30 天。
- **前端**：新增系统请求日志管理页面，使用 Tabler、petite-vue 和 apexcharts 展示搜索列表与统计图表。
- **数据库**：新增请求日志表及必要索引，用于按访问时间、请求路径、客户端 IP 查询与统计。
- **原生镜像**：新增管理 API 的请求/响应 DTO 需要检查并注册到 `JsonReflectionConfiguration`。
