## Why

单节点趋势页面只能回答“某个节点怎么样”，管理员仍需要逐个打开节点才能比较整体负载。新增所有节点趋势总览后，管理员可以在一个页面横向观察节点使用分布、峰值变化和低利用率节点，更快做扩缩节点判断。

## What Changes

- 新增所有节点在线账户趋势总览页面，展示指定时间范围内各节点每日在线账户统计。
- 新增总览摘要与趋势 API，支持按近 7 天、近 30 天、近 90 天切换。
- 在节点管理界面增加进入所有节点趋势总览的入口。
- 总览页复用已有 `node_online_account_daily_stats` 日统计数据和系统监控页图表风格，不新增新的采集口径。
- 总览页应能帮助管理员快速发现高峰节点、低利用率节点和整体在线账户变化趋势。

## Capabilities

### New Capabilities

### Modified Capabilities

- `node-online-account-visibility`: 增加所有节点每日在线账户趋势总览、节点横向对比和节点管理页总览入口要求。

## Impact

- 后端：扩展节点在线账户统计服务与控制器，新增所有节点摘要、排行或趋势数据 DTO。
- 前端：新增所有节点趋势 Qute 页面和 JavaScript，复用 ApexCharts、Tabler 和 petite-vue 模式。
- 控制台导航：在节点管理相关位置增加总览入口，可作为 `/console/vpn/node-online-account-overview` 之类的独立页面。
- 数据：继续读取已有 `node_online_account_daily_stats` 日统计表；不改变单节点统计任务和去重口径。
- 原生镜像：新增 DTO 如用于控制器响应或 JSON 序列化，需要登记到 `JsonReflectionConfiguration`。
