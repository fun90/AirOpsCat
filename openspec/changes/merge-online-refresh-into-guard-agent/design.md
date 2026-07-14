## Context

当前在线状态有两条采集路径：

- `account-online-refresh` 定时任务由中心 SSH 到每台服务器，读取 sing-box Clash API
  `/connections`，再写入 `account_online_ip`。
- `airopscat-guard-agent` 已经在节点本机周期读取同一个 `/connections`，聚合账号
  连接数和去重 IP 后通过 `guard-sync` 上报中心。

这两条路径读取同一事实源，但节奏和失败域不同。在线状态刷新仍是分钟级中心采集，
guard 则是秒级节点推送；继续并存会造成重复解析、重复 SSH、告警与在线视图不同步。

## Goals / Non-Goals

**Goals:**

- 复用 guard agent 的单次 `/connections` 采样，同时满足防共享聚合与在线状态刷新。
- 让 `account_online_ip` 由节点推送刷新，降低中心 SSH 采集压力。
- 保留现有在线页面、在线筛选、节点在线趋势、连接数告警的数据查询模型。
- 保持账户、节点、服务器三个维度的在线连接查询能力和现有前端入口不变。
- 对 agent 故障节点提供可观测状态和可配置回退。

**Non-Goals:**

- 不改变管理端按需查看完整连接列表、断开单条连接、断开全部连接的 Clash API 路径。
- 不新增在线状态主表；在线查询仍以 `account_online_ip` 为事实表。
- 不把 guard agent 做成通用连接管理代理。
- 不改变账户、节点、服务器在线连接查询 API 的语义和返回字段。

## Decisions

### 1. 在 `guard-sync` 请求中增加在线 IP 聚合记录

`GuardSyncRequest` 保留现有 `accounts` 聚合字段，并新增 `onlineAccountIps` 聚合字段。
每条记录包含：

- `accountNo`
- `nodeTag`
- `clientIps`
- `connections`（可选，包含 `clientIp`、`connectionId`、`start`）

理由：防共享判定需要账户级连接数和去重 IP，在线页面主要依赖账号、节点和客户端
IP 可见性。让 agent 一次解析 `/connections` 后同时生成防共享聚合与在线 IP 聚合，
避免在高连接数场景把每条连接都塞进 `guard-sync` 请求体。

`connections` 默认不发送，只在 `guard_include_connection_refs` 开启时携带，用于为后续
按连接关闭预留 `connectionId` 与 `start`，避免现在的高频同步默认回到逐连接大包。
agent 上传 `guard-sync` 请求体时使用 gzip 压缩，中心按 `Content-Encoding: gzip`
解压后再进入 JSON 反序列化。

备选方案：继续上报逐连接明细。该方案能保留连接 ID 与开始时间，但请求体随连接数
线性增长，连接很多时会明显增加中心入口流量和 JSON 解析压力。

### 2. `guard-sync` 同步刷新 `account_online_ip`

中心处理 `guard-sync` 时，在完成 guard 聚合后调用在线刷新服务，把
`onlineAccountIps` 写入 `account_online_ip`。节点标识仍按 `nodeTag` 映射到
AirOpsCat `Node`。

理由：现有页面和统计服务已经依赖 `account_online_ip`，复用它能把前端和大部分查询
逻辑保持不变。

备选方案：新增 guard 在线状态表。该方案会引入双表同步和迁移成本，收益不足。

### 3. 在线刷新任务改为新鲜度检查与回退入口

`account-online-refresh` 不再默认执行中心 SSH 全量采集。它改为：

- 检查节点 guard 上报是否过期，输出日志或告警；
- 在显式开启回退配置时，对过期节点临时执行 Clash API 采集。

理由：保留运维入口，避免未升级节点静默丢失在线数据，同时让常规路径从中心拉取
迁移为节点推送。

备选方案：立即删除任务。该方案迁移风险较高，旧节点或 agent 故障时不易诊断。

### 4. 管理端完整连接管理继续按需 Clash API

服务器连接详情、断开单条连接、断开全部连接继续使用现有
`SingBoxOnlineConnectionService.getServerConnections/delete*` 路径。

理由：guard 上报的是在线刷新必要字段，不包含完整连接详情；高频请求体不应承载
管理端偶发使用的完整字段。

### 5. 查询能力保持三维度不变

`account_online_ip` 继续作为在线查询事实表，现有三类查询能力保持：

- 账户维度：按 `accountNo` 查询当前有效在线连接。
- 节点维度：按 AirOpsCat 节点 ID 查询当前有效在线连接。
- 服务器维度：按服务器 ID/IP 查询当前有效在线账户或连接记录。

理由：本变更只替换在线状态的刷新来源，不改变管理端信息架构。账户、节点、服务器
分别对应运营排查中的不同问题入口，任何一个维度丢失都会造成现有工作流回退。

## Risks / Trade-offs

- [Risk] `guard-sync` 请求体随在线 IP 数增长 → Mitigation:
  在线刷新使用账号与节点维度的 IP 聚合记录；逐连接引用为显式开关，默认不上报；
  请求体使用 gzip 压缩上传。
- [Risk] 节点 agent 故障后在线状态不刷新 → Mitigation: 在线窗口自然过期，同时
  定时任务检查 guard 新鲜度并告警；必要时启用中心采集回退。
- [Risk] `guard-sync` 同步写库影响响应时延 → Mitigation: 批量 upsert，失败时记录
  在线刷新错误但不影响返回配额结论；必要时后续改为队列异步写库。
## Migration Plan

1. 扩展中心 DTO 和 `guard-sync` 处理逻辑，消费 `onlineAccountIps` 聚合字段。
2. 更新 `02-guard-agent.sh`，从同一次 `/connections` 解析结果生成在线 IP 聚合记录。
3. 部署中心后重装或重启节点 guard agent。
4. 观察 `account_online_ip` 刷新、新鲜度检查、在线页面和告警。
5. 所有节点升级后，关闭中心 Clash API 回退采集。

回滚策略：保留中心 Clash API 回退开关；如新 agent 上报异常，关闭 guard 在线刷新并
临时恢复中心定时采集。

## Open Questions

- 是否需要新增节点 guard 上报状态表，还是先用内存状态和日志满足迁移观测？
- `guard-sync` 在线刷新失败时，是否应在响应体中回传 agent 可见的诊断字段？
