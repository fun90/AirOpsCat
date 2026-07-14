## Why

当前服务器 SSH 认证模型已经暴露“密码”和“密钥”两种方式，但连接测试仍是模拟结果，远程操作路径中也存在多处重复构造 SSH 配置的逻辑。需要把密钥登录整理成可验证、可复用、并在 GraalVM Native 模式下同等可用的正式能力，避免管理员保存密钥后部署、维护、监控等操作在不同运行模式或不同业务入口表现不一致。

## What Changes

- 将服务器 SSH 密钥登录定义为一等支持能力：`authType=KEY` 时，服务器认证内容按私钥内容处理。
- 将服务器连接测试从模拟结果改为真实 SSH 连接验证，覆盖密码认证和密钥认证。
- 统一服务器到 `SshConfig` 的构造逻辑，供连接测试、节点部署、服务器配置、服务器维护、监控采集、在线连接查询等 SSH 入口复用。
- 明确 Native 模式验收：JVM 产物与 GraalVM Native 产物必须使用同一认证语义，并能完成密钥登录相关 SSH 操作。
- 第一阶段支持直接粘贴私钥内容；不引入服务器本地私钥文件路径作为业务输入。
- 第一阶段暂不支持带密码短语的私钥，界面和校验需给出明确边界。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `server-and-node-deployment`: 扩展服务器连接和 SSH 远程操作要求，明确密码认证、密钥认证、真实连接测试，以及 Native 模式一致性。

## Impact

- 后端：`ServerService`、`ServerController`、SSH 连接服务、各处构造 `SshConfig` 的部署/维护/监控/配置/在线连接服务。
- 前端：服务器新增/编辑认证信息表单、连接测试展示文案和校验提示。
- Native：`META-INF/native-image` 配置、JSch 相关安全服务和资源配置、Native 构建验证流程。
- 测试：新增或调整 SSH 配置构造单元测试、连接测试行为测试；补充 JVM 与 Native 的人工或自动验收步骤。
