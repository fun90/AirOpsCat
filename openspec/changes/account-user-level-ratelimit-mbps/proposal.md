## Why

现有限速实现依赖 Python Agent 轮询 Clash API 再断连来模拟限速，控制精度低且对正常连接有干扰。sing-box 源码已改造，支持在 inbound users 中直接配置 `download_mbps` / `upload_mbps` 实现内核级用户限速，应将限速信息从 Account 透传到 sing-box 配置以利用该能力。

## What Changes

- Account 数据模型新增 `download_mbps`（下行，Mbps）和 `upload_mbps`（上行，Mbps）两个可空整数字段
- DB 迁移脚本在 account 表追加对应列
- `AccountDto` / `AccountRequest` 增加字段，账号表单增加上行/下行限速输入框
- `NodeClient` record 增加 `downloadMbps` / `uploadMbps`；部署数据加载时透传账号速度
- `SingBoxConfigBuilder` 在构建 vless、vless-reality、hysteria2 用户对象时写入 `download_mbps` / `upload_mbps`（字段为空则不写入，表示不限速）
- 现有 `Account.speed`（KB/s）、`RateLimitService`、Python Agent 不变，继续负责超配降速

## Capabilities

### New Capabilities
- `account-native-user-ratelimit`：账号维度的原生上下行限速配置，管理员可为每个账号单独设置 Mbps 级别的下行/上行速度上限，部署时自动写入 sing-box inbound users

### Modified Capabilities
- `account-management`：账号新增 `downloadMbps` / `uploadMbps` 可选字段，编辑/创建表单增加对应输入框
- `server-and-node-deployment`：节点部署快照（`NodeClient`）携带速度字段，sing-box 配置构建器将速度写入 inbound users

## Impact

- **DB**：account 表新增 2 列（nullable INT）
- **Java**：`Account`、`AccountDto`、`AccountRequest`、`NodeClient`、`DeploymentDataLoader`、`SingBoxConfigBuilder`
- **Qute 模板**：账号管理编辑弹窗（`accounts.html` 或对应模板）
- **JsonReflectionConfiguration**：若 `AccountDto` / `AccountRequest` 已注册则无额外操作，否则需检查
- **不影响**：`RateLimitService`、`AccountTrafficLimitService`、Python Agent、订阅生成
