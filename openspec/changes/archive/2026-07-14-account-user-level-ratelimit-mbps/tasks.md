## 1. 数据库迁移

- [x] 1.1 创建迁移脚本 `src/main/resources/db/migration/V2026052301__add_account_ratelimit_mbps.sql`，在 account 表追加 `download_mbps INT NULL` 和 `upload_mbps INT NULL` 两列

## 2. 实体与 DTO

- [x] 2.1 在 `Account` 实体（`src/main/java/com/fun90/airopscat/model/entity/Account.java`）新增 `downloadMbps`（Integer）和 `uploadMbps`（Integer）字段，加 `@Column(name = "download_mbps")` / `@Column(name = "upload_mbps")`
- [x] 2.2 在 `AccountDto`（`src/main/java/com/fun90/airopscat/model/dto/AccountDto.java`）新增 `downloadMbps` 和 `uploadMbps` 字段
- [x] 2.3 在 `AccountRequest`（`src/main/java/com/fun90/airopscat/model/dto/AccountRequest.java`）新增 `downloadMbps` 和 `uploadMbps` 字段
- [x] 2.4 确认 `AccountDto` 和 `AccountRequest` 已在 `JsonReflectionConfiguration` 注册（当前第 44、48 行已注册，无需改动）

## 3. 部署快照透传

- [x] 3.1 在 `NodeClient` record（`src/main/java/com/fun90/airopscat/model/dto/deployment/NodeClient.java`）新增 `downloadMbps`（Integer）和 `uploadMbps`（Integer）字段
- [x] 3.2 在 `DeploymentDataLoader.toVlessClient()`（`src/main/java/com/fun90/airopscat/service/deployment/DeploymentDataLoader.java` 第 335 行）将 `account.getDownloadMbps()` 和 `account.getUploadMbps()` 透传到 `NodeClient` 构造器

## 4. sing-box 配置构建

- [x] 4.1 在 `SingBoxConfigBuilder.buildVlessUsers()`（`src/main/java/com/fun90/airopscat/singbox/SingBoxConfigBuilder.java` 第 135 行）中，当 `client.downloadMbps()` 非空且 > 0 时写入 `download_mbps`，`client.uploadMbps()` 非空且 > 0 时写入 `upload_mbps`
- [x] 4.2 在 `SingBoxConfigBuilder.buildHysteria2Users()`（第 149 行）做同样处理

## 5. UI 表单

- [x] 5.1 在 `src/main/resources/templates/person/account/modals.html` 的新建弹窗（第 83-89 行附近）追加下行限速（Mbps）和上行限速（Mbps）两个输入框，绑定 `newItem.downloadMbps` 和 `newItem.uploadMbps`
- [x] 5.2 在编辑弹窗（第 231-237 行附近）追加同样的两个输入框，绑定 `editedItem.downloadMbps` 和 `editedItem.uploadMbps`

## 6. 验证

- [x] 6.1 在 AccountService（或 AccountController）中确认 `AccountRequest` 的新字段被正确映射到 Account 实体并保存
- [x] 6.2 手动测试：新建/编辑账号填写限速值，重新部署节点，检查生成的 sing-box 配置中对应 inbound user 包含 `download_mbps` / `upload_mbps` 字段
- [x] 6.3 手动测试：空限速值的账号部署后 sing-box 配置中 inbound user 不含限速字段
