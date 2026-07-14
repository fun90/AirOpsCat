## ADDED Requirements

### Requirement: guard-sync 必须接收在线 IP 聚合记录
系统 SHALL 允许节点 guard agent 在 `guard-sync` 请求中上报本节点当前在线 IP 聚合记录，字段包含账号、节点标识和去重客户端 IP 列表，并允许通过可选连接引用预留连接 ID 与连接开始时间。

#### Scenario: 节点上报在线 IP 聚合记录
- **WHEN** guard agent 读取 Clash API 连接列表并发起 `guard-sync`
- **THEN** 请求体包含用于防共享判定的账户聚合数据
- **AND** 请求体包含用于刷新在线状态表的在线 IP 聚合记录

#### Scenario: 节点开启连接引用预留
- **WHEN** 节点开启 guard 连接引用上报开关
- **THEN** 在线 IP 聚合记录包含可选 `connections` 列表
- **AND** 每条连接引用包含客户端 IP、连接 ID 和连接开始时间

#### Scenario: 节点未开启连接引用预留
- **WHEN** 节点未开启 guard 连接引用上报开关
- **THEN** 在线 IP 聚合记录不包含逐连接引用
- **AND** 请求体大小主要随去重客户端 IP 数增长

#### Scenario: 节点压缩 guard-sync 请求体
- **WHEN** guard agent 向中心发起 `guard-sync`
- **THEN** agent 使用 gzip 压缩请求体
- **AND** 请求头包含 `Content-Encoding: gzip`
- **AND** 中心在 JSON 反序列化前完成解压

### Requirement: guard-sync 必须刷新在线状态表
系统 SHALL 在处理 `guard-sync` 时使用在线 IP 聚合记录刷新 `account_online_ip`，使账户、服务器、节点在线视图复用同一份在线记录。

#### Scenario: 在线 IP 聚合记录有效
- **WHEN** `guard-sync` 请求包含有效账号和客户端 IP 的在线 IP 聚合记录
- **THEN** 系统为对应账号、客户端 IP 和节点标识 upsert 在线记录
- **AND** 系统保存服务器 IP、合成连接 ID、节点标识和最后在线时间

#### Scenario: 在线 IP 聚合记录包含连接引用
- **WHEN** `guard-sync` 请求中的在线 IP 聚合记录包含有效连接引用
- **THEN** 系统优先使用连接引用中的连接 ID upsert 在线记录
- **AND** 系统使用连接引用中的开始时间作为会话开始时间

#### Scenario: 在线 IP 聚合记录缺少必要字段
- **WHEN** 某条在线 IP 聚合记录缺少账号或客户端 IP
- **THEN** 系统跳过该记录或其中无效 IP
- **AND** 系统继续处理同一请求中的其他记录

#### Scenario: 在线 IP 聚合记录包含节点标识
- **WHEN** 在线 IP 聚合记录中的节点标识可映射到 AirOpsCat 节点
- **THEN** 系统在在线记录中保存对应节点 ID

### Requirement: 在线刷新失败不得阻断配额下发
系统 MUST 将在线状态刷新失败与防共享配额下发隔离，避免在线写库异常导致节点拿不到配额结论。

#### Scenario: 在线状态刷新失败
- **WHEN** `guard-sync` 的在线状态写库发生异常
- **THEN** 系统记录失败原因
- **AND** 系统仍返回本次防共享配额响应

### Requirement: 系统必须暴露 guard 在线上报新鲜度
系统 SHALL 能识别节点 guard 在线明细上报是否新鲜，供迁移、排障和回退判断使用。

#### Scenario: 节点持续上报在线 IP 聚合记录
- **WHEN** 节点在配置的新鲜度窗口内完成 guard 在线 IP 聚合记录上报
- **THEN** 系统将该节点视为在线刷新链路健康

#### Scenario: 节点上报过期
- **WHEN** 节点超过配置的新鲜度窗口未完成 guard 在线 IP 聚合记录上报
- **THEN** 系统将该节点视为在线刷新链路过期
- **AND** 系统在日志、告警或管理视图中提供可诊断信息
