## Why

AirOpsCat 当前的“批量调整节点标签”只支持整体覆盖，前端弹窗和后端服务都默认把所选节点的标签替换成勾选结果。实际运维场景里，经常需要在保留现有标签的前提下给一批节点追加新标签，因此需要在不破坏现有默认行为的前提下支持“覆盖 / 新增”两种模式。

## What Changes

- 为节点批量调整标签接口增加一个模式参数，用于区分“覆盖现有标签”和“在现有标签基础上新增”。
- 更新节点批量调整标签弹窗，允许管理员在提交前明确选择覆盖或新增模式，并显示与当前模式一致的提示文案。
- 调整批量标签更新逻辑，使“新增”模式只补充缺失标签而不移除现有标签，“覆盖”模式保持当前整体替换行为。
- 统一批量调整结果反馈，返回更新数量与未变化数量，并在两种模式下都将实际变更过的节点标记为未部署。

## Capabilities

### New Capabilities
- `node-batch-tag-adjustment`: Covers batch node tag updates that support both replace and append behaviors in the admin console and backend API.

### Modified Capabilities

None.

## Impact

- Affected backend files include `src/main/java/com/fun90/airopscat/controller/NodeController.java`, `src/main/java/com/fun90/airopscat/service/NodeService.java`, and `src/main/java/com/fun90/airopscat/model/dto/NodeBatchTagUpdateRequest.java`.
- Affected frontend files include `src/main/resources/templates/vpn/node/content.html` and `src/main/resources/templates/vpn/node/modals.html`, plus any associated page script logic already embedded in the node console page.
- The change keeps the existing batch tag endpoint shape compatible by defaulting unspecified mode to replace, avoiding migration work for existing callers.
