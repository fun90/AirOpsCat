## Purpose
Define server inventory, node lifecycle, deployment orchestration, deployment-state semantics, and remote core management behavior.
## Requirements
### Requirement: Administrators SHALL manage server inventory and connectivity
The system SHALL provide admin APIs for server lifecycle management, server configuration preview, supplier and auth-type metadata, SSH connectivity testing, enablement toggles, renewal, and traffic calibration.

#### Scenario: Server connectivity can be validated before operations
- **WHEN** an administrator invokes the server connection test endpoint
- **THEN** the system validates the configured connection details and returns the test result

#### Scenario: Server lifecycle actions are available
- **WHEN** an administrator manages a server through the admin API
- **THEN** the system supports create, update, enable, disable, renew, and related operational actions

### Requirement: Administrators SHALL manage nodes, route rules, and tags used for deployment authorization
The system SHALL provide APIs for node lifecycle management, available-port checks, default inbound generation, node copy, route-rule lifecycle actions, and tag-to-account or tag-to-node authorization relationships. Node lifecycle APIs SHALL preserve deployment-state semantics when node changes affect remote configuration.

#### Scenario: Node configuration can be prepared before deployment
- **WHEN** an administrator requests node metadata such as available ports, core types, protocols, or default inbound data
- **THEN** the system returns the information needed to configure the node

#### Scenario: Node changes require deployment refresh
- **WHEN** an administrator creates a node, edits deployment-affecting node fields, toggles enablement, changes node tag bindings, or restores a deployment version
- **THEN** the system marks the affected node as pending deployment

#### Scenario: Tag relationships can authorize accounts and nodes
- **WHEN** an administrator manages tag bindings for accounts or nodes
- **THEN** the system persists and exposes those authorization relationships for later subscription and routing use

### Requirement: Node deployments SHALL support execution, batching, version history, restore, and core switching

系统 SHALL 在构建 sing-box inbound users 时支持外部传入运行时限速覆盖，使限速同步路径可在不触发完整部署流程的情况下重建带覆盖的配置。

#### Scenario: 带限速覆盖构建 sing-box 配置
- **WHEN** `SingBoxConfigBuilder.build()` 被调用时传入非空的限速覆盖 map（key 为 accountNo）
- **THEN** 构建器 SHALL 对 map 中存在的账号用覆盖值替换 `NodeClient` 中的 `downloadMbps`/`uploadMbps`
- **AND** 不在 map 中的账号 SHALL 保持 `NodeClient` 原始值

#### Scenario: 不传覆盖时行为不变
- **WHEN** `SingBoxConfigBuilder.build()` 被调用时未传入限速覆盖（null 或空 map）
- **THEN** 构建器 SHALL 与原有行为完全一致，使用 `NodeClient` 中的原始限速值

### Requirement: Remote installation and core management SHALL operate through server-side execution abstractions
The system SHALL support one-click install script discovery, preview, execution, and protocol-specific core management through SSH-backed remote operations. When executing maintenance scripts, the system SHALL inject server context variables including `bandwidth_day` (the day-of-month of the server's billing cycle start date, defaulting to `1` if unset) in addition to existing variables (`server_ip`, `server_host`, `server_ssh_port`, `airopscat_domain`, `airopscat_api_token`).

#### Scenario: Install script can be previewed before execution
- **WHEN** an administrator requests preview for an install script
- **THEN** the system returns the script content or prepared execution content without running it

#### Scenario: Core-specific runtime actions can be delegated
- **WHEN** the system is asked to start, stop, restart, inspect, or switch a supported core implementation
- **THEN** it routes the request through the matching core-management strategy for that node or server context

#### Scenario: bandwidth_day 变量在脚本执行时注入
- **WHEN** 系统执行运维脚本（如 `01-system-init.sh`）
- **THEN** 环境中包含 `bandwidth_day` 变量，值为该服务器 `bandwidthDate` 字段的日期中的天数
- **AND** 若服务器未配置 `bandwidthDate`，`bandwidth_day` 默认为 `1`

---

### Requirement: 节点部署必须保留流量超额账户

系统 SHALL 在生成节点部署客户端列表时保留仍然有效且已授权到节点的流量超额账户，不得仅因账户当前周期流量达到或超过配额而从 sing-box 用户配置中剔除。

#### Scenario: 已授权账户流量超额
- **WHEN** 节点部署数据加载器为 sing-box 节点生成客户端列表，且某个已授权有效账户当前周期流量已达到或超过有效配额
- **THEN** 系统 SHALL 仍将该账户加入节点客户端列表

#### Scenario: 已授权账户未超额
- **WHEN** 节点部署数据加载器为 sing-box 节点生成客户端列表，且某个已授权有效账户当前周期流量未超过有效配额
- **THEN** 系统 SHALL 将该账户加入节点客户端列表

### Requirement: 节点客户端不得承载流量超额限速字段

系统 SHALL 保持节点客户端数据只表达核心用户身份信息，不在 `NodeClient` 中承载流量超额限速值；流量超额限速 SHALL 通过现有限速同步配置下发。

#### Scenario: 生成节点客户端数据
- **WHEN** 节点部署数据加载器将账户转换为节点客户端数据
- **THEN** 节点客户端数据 SHALL 包含账户 UUID、账户号和协议所需 flow
- **AND** 节点客户端数据 SHALL 不包含 speed 字段

### Requirement: 系统初始化安装的监控采集脚本必须按指定口径采集网络与内存

系统 SHALL 在 `01-system-init.sh` 安装的服务器监控 collector 中，只基于默认路由网卡采集网络 rx/tx bytes，并使用 `MemTotal - MemFree - Buffers - Cached - SReclaimable` 公式计算内存已用字节。

#### Scenario: 采集默认路由网卡流量

- **WHEN** collector 执行 `read_network_totals`
- **THEN** 系统 SHALL 通过 `ip route show default` 获取默认路由网卡
- **AND** 系统 SHALL 只读取该网卡在 `/proc/net/dev` 中的 rx bytes 与 tx bytes
- **AND** 系统 SHALL 不汇总其他非默认路由网卡的流量计数

#### Scenario: 默认路由网卡不可用

- **WHEN** collector 无法从 `ip route show default` 获取网卡，或 `/proc/net/dev` 中不存在该网卡
- **THEN** 系统 SHALL 输出 `0 0` 作为网络 rx/tx bytes
- **AND** collector SHALL 继续输出其他监控字段

#### Scenario: 按指定公式计算内存使用量

- **WHEN** collector 执行 `read_memory_stats`
- **THEN** 系统 SHALL 从 `/proc/meminfo` 读取 `MemTotal`、`MemFree`、`Buffers`、`Cached` 与 `SReclaimable`
- **AND** 系统 SHALL 按 `MemTotal - MemFree - Buffers - Cached - SReclaimable` 计算 `memoryUsedBytes`
- **AND** 系统 SHALL 基于该 `memoryUsedBytes` 与 `MemTotal` 计算 `memoryUsage`

#### Scenario: SReclaimable 字段缺失

- **WHEN** `/proc/meminfo` 未提供 `SReclaimable`
- **THEN** 系统 SHALL 将 `SReclaimable` 视为 0 参与内存使用量计算
- **AND** collector SHALL 保持输出 `memoryUsage`、`memoryUsedBytes` 与 `memoryTotalBytes`

### Requirement: 服务器 SSH 认证必须支持密码和密钥

系统 SHALL 在服务器库存、连接测试和所有 SSH 远程操作中支持密码认证与密钥认证。`authType=PASSWORD` 时，认证内容 SHALL 作为 SSH 密码使用；`authType=KEY` 时，认证内容 SHALL 作为未加密私钥内容使用。

#### Scenario: 使用密码认证构造 SSH 连接
- **WHEN** 管理员保存 `authType=PASSWORD` 且包含密码认证内容的服务器
- **THEN** 系统 SHALL 在连接测试和远程操作中将该认证内容作为 SSH 密码传入连接配置

#### Scenario: 使用密钥认证构造 SSH 连接
- **WHEN** 管理员保存 `authType=KEY` 且包含私钥内容的服务器
- **THEN** 系统 SHALL 在连接测试和远程操作中将该认证内容作为 SSH 私钥内容传入连接配置

#### Scenario: 不支持带密码短语的私钥
- **WHEN** 管理员尝试使用需要密码短语的私钥内容进行密钥认证
- **THEN** 系统 SHALL 连接失败并返回不包含私钥内容的失败信息

### Requirement: 服务器连接测试必须执行真实 SSH 验证

系统 SHALL 在服务器连接测试接口中创建真实 SSH 会话并执行轻量远程命令，以验证主机、端口、用户名、认证方式和 exec 通道可用性，不得返回固定模拟结果。

#### Scenario: 密码服务器连接测试成功
- **WHEN** 管理员对密码认证服务器发起连接测试，且服务器 SSH 信息有效
- **THEN** 系统 SHALL 返回连接成功

#### Scenario: 密钥服务器连接测试成功
- **WHEN** 管理员对密钥认证服务器发起连接测试，且服务器 SSH 信息和私钥内容有效
- **THEN** 系统 SHALL 返回连接成功

#### Scenario: 连接测试失败
- **WHEN** 管理员发起连接测试但主机不可达、认证失败或命令执行失败
- **THEN** 系统 SHALL 返回连接失败
- **AND** 系统 SHALL 不在响应或日志中泄露密码、私钥内容或其他认证秘密

### Requirement: SSH 配置构造必须在远程操作中一致复用

系统 SHALL 通过统一逻辑将服务器认证信息转换为 `SshConfig`，并在节点部署、服务器配置同步、服务器维护、监控采集、在线连接查询和连接测试等 SSH 入口中复用该逻辑。

#### Scenario: 所有 SSH 入口使用相同认证语义
- **WHEN** 某台服务器配置为 `authType=KEY`
- **THEN** 节点部署、服务器配置同步、服务器维护、监控采集、在线连接查询和连接测试 SHALL 均使用私钥内容认证

#### Scenario: 默认 SSH 参数保持一致
- **WHEN** 服务器未配置 SSH 端口或用户名为空
- **THEN** 统一 SSH 配置构造逻辑 SHALL 使用项目既有默认端口和默认用户名策略

### Requirement: SSH 密钥登录必须支持 Native 模式

系统 SHALL 在 JVM 模式和 GraalVM Native 模式下保持服务器 SSH 密钥登录行为一致。Native 产物 SHALL 能使用支持范围内的私钥内容完成服务器连接测试和 SSH 远程命令执行。

#### Scenario: JVM 模式密钥连接测试
- **WHEN** 应用以 JVM 模式运行，且管理员对有效密钥认证服务器发起连接测试
- **THEN** 系统 SHALL 使用私钥内容完成真实 SSH 连接测试并返回成功

#### Scenario: Native 模式密钥连接测试
- **WHEN** 应用以 GraalVM Native 模式运行，且管理员对有效密钥认证服务器发起连接测试
- **THEN** 系统 SHALL 使用私钥内容完成真实 SSH 连接测试并返回成功

#### Scenario: Native 模式远程操作
- **WHEN** 应用以 GraalVM Native 模式运行，且系统对有效密钥认证服务器执行 SSH 远程操作
- **THEN** 系统 SHALL 能建立 SSH 会话并执行远程命令

### Requirement: `01-system-init.sh` SHALL 安装并配置 vnstat

系统初始化脚本 `01-system-init.sh` SHALL 安装 vnstat 软件包，根据注入的 `bandwidth_day` 变量配置 `/etc/vnstat.conf` 中的 `MonthRotate`，自动探测主出口网卡并注册至 vnstat，启动并设置 vnstat 服务开机自启。

#### Scenario: vnstat 安装与 MonthRotate 配置
- **WHEN** 在服务器上执行 `01-system-init.sh`，且环境中 `bandwidth_day=15`
- **THEN** 系统安装 vnstat，将 `/etc/vnstat.conf` 中 `MonthRotate` 设为 `15`
- **AND** 启动 vnstat 服务

#### Scenario: bandwidth_day 未注入时使用默认值
- **WHEN** 在服务器上执行 `01-system-init.sh`，且环境中未设置 `bandwidth_day`
- **THEN** `MonthRotate` 设为 `1`

#### Scenario: 主出口网卡自动探测并注册
- **WHEN** `01-system-init.sh` 完成 vnstat 安装
- **THEN** 通过 `ip route get 1.1.1.1` 探测主出口网卡（探测失败时 fallback `eth0`）
- **AND** 执行 `vnstat --add -i <iface>` 注册该网卡（幂等，已存在不报错）

### Requirement: 节点部署快照携带账号限速信息
系统 SHALL 在构建节点部署快照时，将账号的 `downloadMbps` 和 `uploadMbps` 包含在 `NodeClient` 中，以便 sing-box 配置构建器注入速度字段。

#### Scenario: 部署快照中的 NodeClient 携带速度字段
- **WHEN** 系统为某节点构建部署快照，且该节点关联账号设置了 `downloadMbps` 或 `uploadMbps`
- **THEN** 对应 `NodeClient` 对象 MUST 包含非空的速度字段值

#### Scenario: 未设置限速的账号 NodeClient 速度字段为空
- **WHEN** 系统为某节点构建部署快照，且账号未设置 `downloadMbps` 或 `uploadMbps`
- **THEN** 对应 `NodeClient` 的速度字段 MUST 为空

### Requirement: 节点部署不得持久化服务器完整配置正文

系统 SHALL 根据当前节点、账户、路由和系统配置动态生成 sing-box 配置，并将其直接下发到目标服务器。远端配置校验和重启成功后，系统 MUST 不把完整服务器配置正文持久化到数据库。

#### Scenario: 远端部署成功
- **WHEN** 系统成功生成配置、写入远端、通过配置校验并重启 sing-box
- **THEN** 系统 SHALL 更新节点部署状态并记录节点部署版本
- **AND** 系统 MUST 不创建或更新服务器完整配置正文记录

#### Scenario: 部署结果持久化失败边界
- **WHEN** 远端配置已经生效
- **THEN** 系统 SHALL 不再因为保存服务器配置正文失败而把部署结果返回为失败

### Requirement: 运行任务必须从服务器和节点状态推导目标服务器

系统 SHALL 从有效服务器及其已部署、启用节点推导流量采集和限速同步目标，不得依赖服务器配置快照记录判断 sing-box 是否启用。

#### Scenario: 有效服务器存在已部署启用节点
- **WHEN** 服务器未禁用、未过期、不是外部托管服务器，并且至少关联一个已部署且启用的节点
- **THEN** 系统 SHALL 将该服务器纳入 sing-box 流量采集目标
- **AND** 在全局限速启用时将该服务器纳入限速同步目标

#### Scenario: 服务器没有已部署启用节点
- **WHEN** 服务器没有节点，或其节点均为待部署、待删除或禁用状态
- **THEN** 系统 MUST 不将该服务器纳入流量采集或限速同步目标

#### Scenario: 服务器不可运行
- **WHEN** 服务器已禁用、已过期或属于外部托管服务器
- **THEN** 系统 MUST 跳过该服务器的流量采集、限速同步和配置备份清理

### Requirement: sing-box 配置备份清理必须使用固定路径

系统 SHALL 按 sing-box 单内核约定清理 `/etc/sing-box` 目录中的 `config.json.backup.*` 旧备份，不得依赖数据库保存的服务器配置路径。

#### Scenario: 清理有效服务器备份
- **WHEN** 配置备份清理任务处理一台有效且存在已部署启用节点的服务器
- **THEN** 系统 SHALL 在 `/etc/sing-box` 中查找并清理超过保留期限的 `config.json.backup.*` 文件

### Requirement: 管理端不得提供服务器完整配置持久化管理入口

系统 MUST 不提供服务器完整配置正文的数据库查询、创建、修改、删除或重传能力。管理员需要检查待部署配置时，系统 SHALL 提供基于当前业务数据动态生成的配置预览。

#### Scenario: 管理员预览服务器配置
- **WHEN** 管理员请求服务器配置预览
- **THEN** 系统 SHALL 根据当前节点、账户、路由和系统配置即时生成预览
- **AND** 系统 MUST 不从历史服务器配置正文记录读取预览

#### Scenario: 旧配置管理入口被移除
- **WHEN** 系统升级到本变更版本
- **THEN** `/vpn/server-config` 控制台页面和 `/api/admin/server-configs/**` 管理接口 SHALL 不再存在
