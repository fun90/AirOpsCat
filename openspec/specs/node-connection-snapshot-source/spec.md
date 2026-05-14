# node-connection-snapshot-source Specification

## Purpose
TBD - created by archiving change add-node-connection-snapshot-source. Update Purpose after archive.
## Requirements
### Requirement: 节点必须提供连接快照源
系统 SHALL 在节点服务器上提供独立的连接快照采集能力，由该能力负责读取 sing-box Clash API 的连接列表并生成供其他本机或远程功能消费的快照。

#### Scenario: 采集进程生成快照
- **WHEN** 节点服务器上的 sing-box Clash API 可访问且返回连接列表
- **THEN** 连接快照采集进程生成限速快照和在线状态快照
- **AND** 每份快照包含 schema 版本、生成时间和 TTL

#### Scenario: 采集进程读取失败
- **WHEN** 连接快照采集进程无法访问 Clash API 或解析连接列表失败
- **THEN** 系统记录失败原因
- **AND** 不写入半成品快照

### Requirement: 限速快照必须只包含限速必要字段
系统 SHALL 为限速功能生成裁剪后的高频快照，快照中的每条连接只包含执行限速映射所需的网络协议、客户端 IP、客户端源端口和账号标识。

#### Scenario: 连接包含限速必要字段
- **WHEN** Clash API 连接记录包含 `metadata.authUser`、`metadata.network`、`metadata.sourceIP` 和 `metadata.sourcePort`
- **THEN** 限速快照为该连接写入对应的精简 flow 记录

#### Scenario: 连接缺少限速必要字段
- **WHEN** Clash API 连接记录缺少账号、网络协议、客户端 IP 或客户端源端口
- **THEN** 限速快照跳过该连接

### Requirement: 在线状态快照必须只包含在线刷新必要字段
系统 SHALL 为账号在线状态刷新生成中低频消费的在线状态快照，快照中的每条连接只包含连接 ID、账号、客户端 IP、节点标识和连接开始时间。

#### Scenario: 连接包含在线状态必要字段
- **WHEN** Clash API 连接记录包含账号和客户端 IP
- **THEN** 在线状态快照为该连接写入账号、客户端 IP、连接 ID、节点标识和连接开始时间

#### Scenario: 连接缺少在线状态必要字段
- **WHEN** Clash API 连接记录缺少账号或客户端 IP
- **THEN** 在线状态快照跳过该连接

### Requirement: 快照写入必须具备原子可读语义
系统 MUST 以原子替换方式写入连接快照，使消费方只能读取到上一份完整快照或新一份完整快照。

#### Scenario: 快照写入成功
- **WHEN** 连接快照采集进程完成一次快照内容生成
- **THEN** 系统先写入临时文件
- **AND** 再将临时文件原子替换为正式快照文件

#### Scenario: 快照写入过程中失败
- **WHEN** 连接快照采集进程在写入临时文件或替换正式文件前失败
- **THEN** 已存在的正式快照保持不变

### Requirement: 快照消费方必须拒绝过期快照
系统 SHALL 要求所有连接快照消费方根据快照生成时间和 TTL 判断快照有效性，并拒绝使用过期快照作为当前连接事实。

#### Scenario: 快照仍在 TTL 内
- **WHEN** 消费方读取到的快照生成时间未超过 TTL
- **THEN** 消费方可以使用该快照执行对应业务逻辑

#### Scenario: 快照已经过期
- **WHEN** 消费方读取到的快照生成时间已经超过 TTL
- **THEN** 消费方拒绝将该快照作为当前连接事实
- **AND** 消费方记录可诊断的日志或指标

### Requirement: 管理端完整连接详情不得依赖高频精简快照
系统 SHALL 将高频精简快照限定为限速和在线状态刷新用途，管理端查看完整连接详情和断开连接仍通过 Clash API 或等价的按需连接管理接口执行。

#### Scenario: 管理员查看完整连接详情
- **WHEN** 管理员请求服务器完整连接列表
- **THEN** 系统按需查询 Clash API 或等价连接管理接口
- **AND** 返回完整连接详情所需字段

#### Scenario: 管理员断开连接
- **WHEN** 管理员请求断开指定连接或全部连接
- **THEN** 系统通过 Clash API 或等价连接管理接口执行断开操作
