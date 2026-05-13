## ADDED Requirements

### Requirement: 编辑账户 UUID
系统 SHALL 允许管理员在账户管理页面的编辑弹窗中修改账户 UUID，并在保存成功后持久化该 UUID。

#### Scenario: 管理员手动修改 UUID
- **WHEN** 管理员打开账户编辑弹窗并修改 UUID 输入框后点击保存
- **THEN** 系统 MUST 将新的 UUID 随账户更新请求提交并保存到账户记录

#### Scenario: 管理员重新生成后保存 UUID
- **WHEN** 管理员在账户编辑弹窗中点击重新生成 UUID 并点击保存
- **THEN** 系统 MUST 保存重新生成后的 UUID

#### Scenario: 保存后展示最新 UUID
- **WHEN** 账户 UUID 修改保存成功后管理员查看账户列表或账户详情
- **THEN** 系统 MUST 展示账户当前保存的 UUID

### Requirement: 更新账户 UUID 校验
系统 SHALL 在更新账户时校验 UUID，拒绝空值、非法 UUID 格式以及与其他账户重复的 UUID。

#### Scenario: UUID 为空
- **WHEN** 管理员更新账户时提交空 UUID 或仅包含空白字符的 UUID
- **THEN** 系统 MUST 拒绝保存并返回 UUID 不能为空的错误

#### Scenario: UUID 格式非法
- **WHEN** 管理员更新账户时提交不符合 UUID 格式的值
- **THEN** 系统 MUST 拒绝保存并返回 UUID 格式不合法的错误

#### Scenario: UUID 与其他账户重复
- **WHEN** 管理员更新账户时提交已被其他账户使用的 UUID
- **THEN** 系统 MUST 拒绝保存并返回 UUID 已存在的错误

#### Scenario: UUID 与当前账户原值相同
- **WHEN** 管理员更新账户时提交的 UUID 与当前账户原 UUID 相同
- **THEN** 系统 MUST 允许保存账户的其他字段变更

### Requirement: UUID 变更后的下游读取
系统 SHALL 让订阅生成、节点部署配置和账户搜索读取账户当前 UUID。

#### Scenario: 订阅和部署读取新 UUID
- **WHEN** 账户 UUID 修改成功后管理员生成订阅配置或部署账户关联节点
- **THEN** 系统 MUST 使用账户最新 UUID 生成用户配置

#### Scenario: 搜索新 UUID
- **WHEN** 账户 UUID 修改成功后管理员使用新 UUID 搜索账户
- **THEN** 系统 MUST 能够返回该账户
