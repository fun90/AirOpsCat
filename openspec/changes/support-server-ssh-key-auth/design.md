## Context

服务器实体当前使用 `authType` 区分认证方式，使用加密字段 `auth` 保存认证内容；前端和枚举已经暴露 `PASSWORD` 与 `KEY`，`SshConfig` 与 `JschConnection` 也具备私钥内容登录能力。问题在于连接测试仍返回模拟结果，多个业务服务各自构造 `SshConfig`，Native 模式下的 SSH 密钥算法和资源配置也没有形成明确验收边界。

项目使用 `io.quarkiverse.jsch:quarkus-jsch`，实际底层为 `com.github.mwiede:jsch`，并已有 `META-INF/native-image` 下的 JSch 资源和安全服务配置。密钥登录实现必须保持 JVM 与 GraalVM Native 行为一致。

## Goals / Non-Goals

**Goals:**

- 让 `authType=KEY` 成为服务器 SSH 私钥内容登录的正式行为。
- 用真实 SSH 连接验证替换服务器连接测试的模拟结果。
- 提供单一的服务器 SSH 配置构造入口，减少部署、维护、监控、配置同步、在线连接查询等路径的认证差异。
- 明确 Native 构建下对 RSA、ECDSA、Ed25519 等常见私钥算法的验证要求。
- 保持现有 `server.auth` 加密存储方式，不引入数据库结构变更。

**Non-Goals:**

- 不支持以远程或本地文件路径引用私钥。
- 不支持带密码短语的私钥；后续如需要可单独扩展字段和校验。
- 不更换 SSH 客户端库。
- 不调整 SSH host key 校验策略，本次保持现有 `StrictHostKeyChecking=no` 行为。

## Decisions

### 1. 复用 `server.auth` 保存密码或私钥内容

`server.auth` 已通过 `CryptoConverter` 加密存储，现有 DTO 与前端也已经围绕该字段传递认证内容。继续复用该字段可以避免迁移成本，并保持旧服务器数据兼容。

替代方案是新增 `private_key`、`password` 等独立列。它能让语义更清晰，但会引入迁移、脱敏、兼容旧数据和表单回填复杂度；本次目标是把已有模型补齐成可靠能力，暂不采用。

### 2. 引入统一的服务器 SSH 配置工厂

新增或改造一个集中入口，例如 `ServerSshConfigFactory`，接收 `Server` 或 `ServerDto` 并返回 `SshConfig`。该入口负责默认端口、默认用户名、超时时间、`PASSWORD` 与 `KEY` 的认证映射、私钥内容规范化和不支持 passphrase 的边界校验。

替代方案是继续在各服务内复制 `buildSshConfig`。这会让密钥支持和 Native 修正很容易遗漏某个入口，不适合跨部署、维护、监控的能力。

### 3. 连接测试执行轻量真实命令

服务器连接测试应创建 SSH 连接并执行轻量命令，例如 `echo airopscat-ssh-ok` 或 `true`。这样可以验证认证、会话建立和 exec channel 能力，同时避免修改远程服务器。

替代方案是只调用 `session.connect()`。这能验证认证，但无法确认 exec 通道是否可用；部署和维护依赖 exec，因此连接测试应覆盖 exec。

### 4. Native 支持作为同等验收，不作为可选验证

密钥登录涉及 JSch 类加载、安全 provider、签名算法和私钥解析。实现时需要检查现有 `native-image.properties`、`resource-config.json`、`jni-config.json` 是否足够，并用 Native 产物完成至少一次密钥登录连接测试。

验收可使用本地可用私钥连接脱敏测试主机（例如 `root@104.194.x.x`）。OpenSpec 和代码库不得记录完整测试主机地址、私钥内容或本地私钥路径；实现测试时只从本机环境读取认证材料。

替代方案是只依赖 JVM 测试。这样会把 Native 问题推迟到发布后，风险高于本次补齐成本。

### 5. 第一阶段只支持未加密私钥内容

`SshConfig` 虽然有 `passphrase` 字段，但服务器模型没有持久化密码短语的位置。第一阶段应在 UI 文案和后端校验中明确“不支持带密码短语的私钥”，避免用户误填后得到难以理解的连接失败。

## Risks / Trade-offs

- [Native 密钥算法不可用] → 在任务中加入 Native 构建与真实密钥登录验证；必要时补充安全服务或资源配置。
- [私钥格式差异导致解析失败] → 优先支持常见 OpenSSH 私钥内容，并在连接失败消息中保留可诊断但不泄露密钥的错误摘要。
- [统一工厂改造遗漏业务入口] → 用代码搜索列出全部 `SshConfig` 构造点，并在任务中逐一替换。
- [连接测试执行远程命令带来副作用] → 使用只读轻量命令，不写文件、不修改服务状态。
- [认证内容回显泄露风险] → 保持现有展示行为的基础上，避免日志打印 `auth`、私钥内容或密码短语。

## Migration Plan

无需数据库迁移。已有 `authType=PASSWORD` 的服务器继续按密码认证；已有 `authType=KEY` 的服务器在统一工厂接入后按私钥内容认证。

如实现后发现某类私钥在 Native 下不可用，应先限制 UI/文案中声明的支持格式，并保留密码认证回退路径。回滚时可恢复到原服务内构造逻辑，但连接测试模拟成功不应作为长期回退。

## Open Questions

- 是否需要在本次实现中为连接测试返回更细的错误类型，例如认证失败、主机不可达、私钥格式错误、命令执行失败。
