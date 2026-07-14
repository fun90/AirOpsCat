## Why

当前在线状态刷新仍由中心定时 SSH 到每台服务器读取 Clash API `/connections`，而
guard agent 已经在节点本机以秒级周期读取同一份连接列表。两条链路重复采集同一
数据源，增加中心 SSH 压力，也让在线刷新与防共享判断存在不同步窗口。

将在线刷新并入 guard agent，可以复用节点侧一次连接采样，同时刷新
`account_online_ip` 与防共享聚合状态，减少远程采集开销并让在线视图更接近实时。

## What Changes

- 扩展 `guard-sync` 请求体，允许节点 agent 上报在线刷新所需的连接明细，包括账号、
  客户端 IP、连接 ID、节点标识和连接开始时间。
- AirOpsCat 在处理 `guard-sync` 时，使用同一请求中的连接明细刷新
  `account_online_ip`。
- 在线账号刷新任务从“中心 SSH 主动采集 Clash API”调整为“检查 guard 上报新鲜度
  与触发告警/统计兜底”，不再作为常规数据采集路径。
- 保持现有在线连接查询能力不变：账户、节点、服务器三个维度仍可查询当前有效在线
  连接，返回字段和页面入口保持兼容。
- 保留管理端按需查看完整连接列表和断开连接的 Clash API 能力；该能力不并入
  guard-sync。
- 增加故障与迁移策略：未部署 guard agent 或上报过期的节点在 UI/日志中可见，并
  可临时启用中心 Clash API 采集作为回退。

## Capabilities

### New Capabilities
- `guard-online-status-refresh`: 节点 guard agent 上报在线连接明细，中心在
  `guard-sync` 中刷新在线状态表。

### Modified Capabilities
- `node-online-account-visibility`: 在线状态记录的数据来源从中心定时 SSH 采集改为
  guard agent 上报，管理页继续复用 `account_online_ip` 作为查询来源。

## Impact

- 后端：`OpenController.guardSync`、guard DTO、`AccountOnlineIpService`、
  `AccountOnlineRefreshTask`、`ProgrammaticTaskManager`、在线告警与节点在线统计。
- API 兼容：保留账户、节点、服务器在线连接查询接口，不改变前端调用路径。
- 节点脚本：`src/main/resources/config/shell/02-guard-agent.sh` 需要在聚合账号总量的
  同时生成在线连接明细。
- 数据库：复用 `account_online_ip`，不新增在线状态表；可能需要记录节点最后
  guard 上报时间的轻量状态。
- 配置：在线刷新相关配置需要从“中心采集间隔”调整为“guard 上报新鲜度 / 回退开关”。
- 兼容性：旧节点未部署 guard agent 时，在线状态不应静默失真，必须有可观测的
  回退或告警路径。
