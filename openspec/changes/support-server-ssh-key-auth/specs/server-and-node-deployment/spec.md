## ADDED Requirements

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
