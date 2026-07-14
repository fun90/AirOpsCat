# vnstat-traffic-chart Specification

## Purpose
TBD - created by archiving change vnstat-timeseries-chart. Update Purpose after archive.
## Requirements
### Requirement: 累计流量趋势图数据接口
`GET /api/admin/server-monitors/{serverId}/charts` 响应 SHALL 包含 `vnstatPoints` 字段，返回选定时间窗口内的 vnstat 时序快照列表。

#### Scenario: 返回时间窗口内的快照
- **WHEN** 请求 `?hours=N`
- **THEN** 响应 `vnstatPoints` 包含 `sampledAt >= now - N hours` 的所有快照，按 `sampledAt` 升序排列，每条含 `sampledAt / rxBytes / txBytes`

#### Scenario: 无 vnstat 数据时返回空数组
- **WHEN** 该服务器在时间窗口内无 vnstat 快照（vnstat 未安装或尚未采集）
- **THEN** `vnstatPoints` 返回空数组 `[]`

### Requirement: 累计流量趋势图渲染
监控页 SHALL 在 `vnstatAvailable=true` 且 `vnstatPoints` 非空时，渲染"累计流量趋势（本月）"面积图。

#### Scenario: 有 vnstat 数据时显示图表
- **WHEN** API 返回 `vnstatAvailable=true` 且 `vnstatPoints.length > 0`
- **THEN** 页面在网络实时速率图旁渲染累计流量趋势图，X 轴为采样时间，Y 轴为字节数（格式化为 B/KB/MB/GB），系列为「累计下载（rx）」和「累计上传（tx）」

#### Scenario: vnstat 未采集时不显示图表卡片
- **WHEN** `vnstatAvailable=false` 或 `vnstatPoints` 为空
- **THEN** 累计流量趋势图卡片不渲染

#### Scenario: 时间窗口切换时图表联动刷新
- **WHEN** 用户切换时间窗口（1小时/6小时/24小时/7天）
- **THEN** 累计流量趋势图随 `vnstatPoints` 数据刷新，与其他图表保持一致

