## ADDED Requirements

### Requirement: 告警管理 REST API
系统 SHALL 提供以下 REST 端点，路径前缀 `/api/alert-states`，需要登录权限：

- `GET /api/alert-states`：分页查询告警列表，支持 `alertType`、`status`、`resourceType`、`page`、`size` 参数
- `GET /api/alert-states/{id}`：查询单条告警详情
- `POST /api/alert-states/{id}/acknowledge`：人工确认告警，body 包含 `acknowledgedBy`
- `DELETE /api/alert-states/{id}`：清除（物理删除）单条告警记录

#### Scenario: 分页查询告警列表
- **WHEN** 客户端发送 `GET /api/alert-states?status=ACTIVE&page=0&size=20`
- **THEN** 系统返回当前 `ACTIVE` 状态的告警分页列表，含 `totalElements`、`totalPages`、`content` 字段

#### Scenario: 按资源类型过滤
- **WHEN** 客户端发送 `GET /api/alert-states?resourceType=account`
- **THEN** 系统仅返回 `resourceType=account` 的告警记录

#### Scenario: 确认告警
- **WHEN** 客户端发送 `POST /api/alert-states/42/acknowledge` 携带 `{"acknowledgedBy":"admin"}`
- **THEN** 系统将 id=42 的告警状态更新为 `ACKNOWLEDGED`，记录 `acknowledgedTime` 和 `acknowledgedBy`，返回更新后的告警对象

#### Scenario: 确认非 ACTIVE 告警
- **WHEN** 客户端尝试确认一条状态为 `RECOVERED` 的告警
- **THEN** 系统返回 HTTP 409，提示告警已不处于 ACTIVE 状态

#### Scenario: 清除告警
- **WHEN** 客户端发送 `DELETE /api/alert-states/42`
- **THEN** 系统从数据库中物理删除该条告警记录，返回 HTTP 204

#### Scenario: 查询不存在的告警
- **WHEN** 客户端请求不存在的告警 ID
- **THEN** 系统返回 HTTP 404

### Requirement: 告警管理控制台页面
系统 SHALL 在控制台新增"告警管理"页面，注册到 `ConsolePageRegistry`，归属 `system` 模块组，菜单名称"告警管理"，URI `/console/system/alert`。

#### Scenario: 访问告警管理页面
- **WHEN** 已登录用户访问 `/console/system/alert`
- **THEN** 系统渲染告警列表页面，展示告警表格并提供筛选控件

#### Scenario: 页面列表展示
- **WHEN** 页面加载
- **THEN** 表格展示以下列：告警类型、资源类型、资源标识、状态（带颜色标签）、严重等级、触发次数、最后触发时间、最后通知时间、摘要（截断）、操作按钮（确认/清除）

#### Scenario: 状态筛选
- **WHEN** 用户选择状态筛选器（全部 / ACTIVE / ACKNOWLEDGED / RECOVERED）并提交
- **THEN** 页面刷新并只展示对应状态的告警记录

#### Scenario: 告警类型筛选
- **WHEN** 用户从下拉框选择告警类型
- **THEN** 页面刷新并只展示对应类型的告警

#### Scenario: 分页导航
- **WHEN** 告警记录超过单页数量（默认 20 条）
- **THEN** 页面底部展示分页控件，用户可翻页

### Requirement: 告警确认交互
系统 SHALL 在告警管理页面提供人工确认功能，点击"确认"按钮后弹出确认对话框，提交后更新告警状态。

#### Scenario: 点击确认按钮
- **WHEN** 用户点击某条 ACTIVE 告警的"确认"按钮
- **THEN** 页面弹出确认模态框，展示告警摘要，并提供"确认"和"取消"操作

#### Scenario: 提交确认
- **WHEN** 用户在模态框中点击"确认"
- **THEN** 系统调用 `POST /api/alert-states/{id}/acknowledge`，告警状态更新为 `ACKNOWLEDGED`，列表刷新

#### Scenario: 已确认状态告警不展示确认按钮
- **WHEN** 告警状态为 `ACKNOWLEDGED` 或 `RECOVERED`
- **THEN** "确认"按钮不可见或置灰

### Requirement: 告警清除交互
系统 SHALL 在告警管理页面提供清除功能，点击"清除"按钮后二次确认，确认后从列表中删除该条记录。

#### Scenario: 点击清除按钮
- **WHEN** 用户点击某条告警的"清除"按钮
- **THEN** 页面弹出二次确认对话框，提示"此操作不可撤销"

#### Scenario: 确认清除
- **WHEN** 用户确认清除操作
- **THEN** 系统调用 `DELETE /api/alert-states/{id}`，告警从列表中消失，页面显示操作成功提示

### Requirement: AlertState DTO 注册到 JsonReflectionConfiguration
系统 SHALL 将新增的 `AlertStatePageVo`、`AlertStateVo`、`AcknowledgeRequest` DTO 注册到 `JsonReflectionConfiguration`，以支持 GraalVM 原生镜像序列化。

#### Scenario: 原生镜像序列化
- **WHEN** 应用以 GraalVM 原生镜像模式运行并调用 `/api/alert-states`
- **THEN** 系统正确序列化 `AlertStatePageVo` 响应，不抛出反射异常
