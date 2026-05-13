## Why

当前账户管理页面在创建账户时支持填写 UUID，但编辑账户时 UUID 字段为只读，管理员无法修正导入、迁移或外部系统同步后产生的不一致 UUID。允许在编辑页修改 UUID，可以减少需要直接改库的运维操作，并保持订阅、节点部署和认证配置中的账户标识可控。

## What Changes

- 账户管理页面的编辑弹窗支持修改账户 UUID，不再将 UUID 字段固定为只读。
- 账户编辑接口接收并保存提交的 UUID，保留空值自动生成策略仅用于创建账户。
- 账户 UUID 修改时必须执行格式校验和唯一性校验，避免写入非法 UUID 或与其他账户冲突。
- UUID 修改成功后，账户详情、列表搜索、订阅生成和节点部署后续读取到新的 UUID。

## Capabilities

### New Capabilities
- `account-management`: 账户管理页面和账户编辑接口中账户基础信息的维护行为，包含 UUID 可编辑、校验和保存要求。

### Modified Capabilities

## Impact

- **前端**：`src/main/resources/templates/person/account/modals.html` 中账户编辑弹窗的 UUID 输入框及相关提交逻辑。
- **接口/DTO**：账户编辑请求对象和 `PUT /api/admin/accounts/{id}` 更新流程。
- **服务**：`AccountService` 的账户更新逻辑、UUID 格式校验、唯一性校验和保存行为。
- **数据**：不新增字段或迁移，继续使用现有 `Account.uuid` 字段。
- **下游链路**：订阅生成、节点部署配置、账户搜索和详情展示继续读取账户当前 UUID。
