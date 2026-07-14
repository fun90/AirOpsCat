## ADDED Requirements

### Requirement: 账号编辑支持原生限速字段
系统 SHALL 在账号创建和编辑接口中支持 `downloadMbps` 和 `uploadMbps` 两个可选整数字段（单位 Mbps），并在账号管理页面的编辑弹窗中展示对应输入框。

#### Scenario: 编辑弹窗显示限速输入框
- **WHEN** 管理员打开账号编辑弹窗
- **THEN** 系统 MUST 显示下行限速（Mbps）和上行限速（Mbps）两个可选输入框，并回显当前账号的值

#### Scenario: 提交合法的限速值
- **WHEN** 管理员填写正整数的 `downloadMbps` 或 `uploadMbps` 并保存
- **THEN** 系统 MUST 接受并持久化该值

#### Scenario: 提交空值表示不限速
- **WHEN** 管理员将限速输入框留空并保存
- **THEN** 系统 MUST 将对应字段置为空（不限速）
