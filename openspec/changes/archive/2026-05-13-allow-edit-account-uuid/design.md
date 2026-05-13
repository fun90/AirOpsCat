## Context

账户管理的新增弹窗允许填写或生成 UUID，编辑弹窗虽然展示 UUID 并提供重新生成按钮，但输入框被标记为只读。前端 `prepareUpdateData()` 已经会提交 `editedItem.uuid`，`AccountRequest` 和 `AccountController.updateAccount()` 也已经包含 `uuid` 字段，当前缺口主要在编辑 UI 的可输入性以及服务层对修改后 UUID 的格式和唯一性保护。

UUID 会被订阅、sing-box 用户配置、节点部署配置和账户搜索读取。修改 UUID 属于管理员修正账户标识的高风险操作，需要在保存前阻止空值、非法格式和与其他账户重复的值，并确保保存成功后后续链路读取账户当前 UUID。

## Goals / Non-Goals

**Goals:**

- 编辑账户弹窗中的 UUID 字段允许管理员手动编辑。
- 保留编辑弹窗中的重新生成 UUID 能力，并让生成值可继续人工微调。
- 更新账户时校验 UUID 非空、格式合法且不与其他账户重复。
- UUID 变更保存后，列表、详情、订阅生成和节点部署均基于最新账户 UUID。

**Non-Goals:**

- 不新增数据库字段或迁移。
- 不改变创建账户时 UUID 留空自动生成的行为。
- 不自动触发节点部署；管理员仍通过现有部署操作发布配置变更。
- 不开放普通用户自助修改账户 UUID。

## Decisions

1. 编辑弹窗解除 `readonly`

   直接移除 `src/main/resources/templates/person/account/modals.html` 中编辑弹窗 UUID 输入框的 `readonly` 属性，保持现有输入组、`v-model="editedItem.uuid"` 和“重新生成”按钮不变。这样改动范围最小，也复用现有 `prepareUpdateData()` 提交流程。

2. 后端服务层作为权威校验点

   在 `AccountService.updateAccount()` 中处理 UUID 标准化、格式校验和唯一性校验。控制器继续负责请求到实体的字段映射，避免把业务规则分散在前端或控制器中。前端可以增加基础必填/格式提示，但不得依赖前端作为唯一保护。

3. UUID 使用 Java 标准解析校验

   使用 `UUID.fromString()` 判断格式，并保存标准小写字符串。相比正则校验，标准库能避免格式边界差异；同时将输入 `trim()` 后保存，可消除管理员误输入首尾空格带来的问题。

4. 唯一性校验排除当前账户

   当提交 UUID 与当前账户原 UUID 相同，允许保存；当其他账户已使用该 UUID，则拒绝更新并返回明确错误。实现时优先复用或新增 `AccountRepository` 查询方法，避免在服务层加载全量账户。

## Risks / Trade-offs

- UUID 修改后已有客户端旧配置会失效 -> 保存本身只更新账户标识，不自动部署或通知；管理员需通过现有配置链接、订阅或部署流程让客户端获取新配置。
- 数据库当前 `uuid` 字段未声明唯一约束 -> 本次先在服务层校验，减少迁移风险；若未来需要强一致性，可另行添加唯一索引迁移。
- `copyNonNullProperties()` 会跳过 null 值 -> 更新接口必须把 UUID 视为必填并在服务层拒绝空值，避免“提交空值但实际保留旧值”的歧义。
- 现有异常到 HTTP 响应的映射可能不统一 -> 实现时需要让非法 UUID 和重复 UUID 返回 400，并提供前端能展示的错误消息。
