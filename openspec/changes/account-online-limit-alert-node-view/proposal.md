## Why

当前定时统计账户在线已经可以从 sing-box 连接信息刷新在线记录，但账户限制仍沿用“最大在线 IP 数”的过时定义，无法准确表达同一 IP 多节点、多连接并发的真实占用。需要去除旧定义，改为账户最大连接数，并在超限时主动告警；同时支持从节点视角查看当前在线账户，方便排查节点负载和异常连接。

## What Changes

- 将账户限制口径从“最大在线 IP 数”调整为“最大连接数”，业务、接口和页面文案不再使用旧定义。
- 清理现有 `maxOnlineIps` 相关代码、DTO、请求字段和前端展示，统一改为 `maxConnections`。
- 在在线账户刷新完成后，按账户聚合当前有效在线连接记录，并与账户最大连接数比较。
- 当账户当前在线连接数超过限制时，通过现有 Bark 通知通道发送告警。
- 新增通用告警状态持久化模型，本期仅接入账户连接数超限告警；现有服务器流量、服务器负载等其他告警本期不改。
- 在节点管理视图中支持查看指定节点当前在线账户。
- 前端增加在线连接数展示和查看在线连接详情入口。
- 在线连接记录展示增加节点区分信息，账户维度查看在线连接时可以识别连接分布在哪些节点上。
- 保留 `account_online_ip` 作为在线连接记录表，但调整唯一记录口径以支持节点区分和最大连接数统计。

## Capabilities

### New Capabilities

- `account-connection-limit-alerting`: 定时在线统计后检查账户在线连接数是否超过账户限制，并发送告警通知。
- `node-online-account-visibility`: 支持在节点上查看当前在线账户，并在账户在线连接记录中展示节点区分信息。

### Modified Capabilities

无。

## Impact

- 影响在线采集链路：`SingBoxOnlineConnectionService`、`AccountOnlineIpService`、`AccountOnlineRefreshTask`。
- 影响账户限制字段与文案：`Account`、`AccountDto`、`AccountRequest`、账户表单和详情展示。
- 影响账户与在线连接查询：`AccountOnlineIpRepository`、`AccountOnlineIpDto`、账户在线详情接口和前端展示。
- 影响节点管理接口与页面：节点或服务器维度在线账户接口、节点管理模板和对应 JS。
- 复用现有 Bark 通知服务与系统配置中心，新增必要的在线超限告警配置项。
- 需要补充原生镜像反射注册检查：新增 DTO 或控制器响应类型时同步更新 `JsonReflectionConfiguration`。
