## 1. 数据模型与迁移

- [x] 1.1 新增 `NodeOnlineAccountDailyStats` 实体，字段包含节点 ID、统计日期、最近在线账户数、峰值在线账户数、累计去重在线账户数、采样次数、最后采样时间、创建时间和更新时间。
- [x] 1.2 新增 `NodeOnlineAccountDailyStatsRepository`，提供按节点和日期范围查询、按当天记录 upsert、统计周期摘要所需查询方法。
- [x] 1.3 新增数据库迁移脚本创建 `node_online_account_daily_stats` 表，并添加 `node_id + stat_date` 唯一索引和查询索引。
- [x] 1.4 确认新增实体和 DTO 是否需要加入 `JsonReflectionConfiguration`，并完成登记。

## 2. 在线账户日统计服务

- [x] 2.1 在 `AccountOnlineIpRepository` 增加按当前有效窗口、逻辑节点分组统计去重账号的方法，并排除 `nodeId` 为空的记录。
- [x] 2.2 新增 `NodeOnlineAccountStatsService`，实现单次采样：读取当前节点在线账号数并写入当天日统计。
- [x] 2.3 在日统计写入逻辑中维护最近采样值、当天峰值、当天累计去重账户数、采样次数和最后采样时间。
- [x] 2.4 新增定时任务并注册到 `ProgrammaticTaskManager`，按配置周期执行节点在线账户日统计采样。
- [x] 2.5 为趋势页读取当天数据时补一次轻量采样，降低页面打开时数据滞后的概率。

## 3. API 与页面数据契约

- [x] 3.1 新增节点在线账户统计 DTO，包括摘要 DTO、图表 DTO 和每日点位 DTO。
- [x] 3.2 新增节点在线账户统计控制器，提供 `/api/admin/node-online-account-stats/{nodeId}/summary` 接口。
- [x] 3.3 新增节点在线账户统计控制器，提供 `/api/admin/node-online-account-stats/{nodeId}/charts?days=` 接口，并限制查询范围为安全天数。
- [x] 3.4 对不存在的节点返回 404；对无统计数据的节点返回可渲染的空摘要和空点位。

## 4. 控制台页面与节点列表入口

- [x] 4.1 在 `ConsolePageRegistry` 注册 `/vpn/node-online-account-stats` 页面，作为不在侧边菜单展示的节点上下文页面。
- [x] 4.2 新增 Qute 页面和内容模板，页面布局参考系统监控页，包含节点摘要、时间范围选择、刷新按钮、返回节点列表按钮和图表区域。
- [x] 4.3 新增 `node-online-account-stats.js`，复用系统监控页的 ApexCharts 主题适配、空状态、加载状态和范围切换交互。
- [x] 4.4 在节点列表页操作菜单中增加“在线趋势”入口，跳转到 `/console/vpn/node-online-account-stats?nodeId=<id>`。
- [x] 4.5 确保节点当前在线数为零时仍显示趋势入口，并且移动端表格布局不被新增入口撑破。

## 5. 验证

- [x] 5.1 运行 OpenSpec 校验，确认 proposal、design、specs 和 tasks 均可被识别。
- [x] 5.2 编译项目，修复新增实体、仓库、DTO、控制器和模板引入的编译问题。
- [x] 5.3 手动验证节点列表入口、趋势页空数据状态、趋势页有数据状态和天数范围切换。
- [x] 5.4 验证同一账号多连接时日统计按账号去重，且峰值在线账户数不会被连接数放大。
