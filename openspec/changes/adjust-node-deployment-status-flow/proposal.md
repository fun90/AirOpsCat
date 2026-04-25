## Why

当前节点部署状态使用 `deployed` 的 0/1 二值语义表示“未部署 / 已部署”。这在普通配置变更后重新部署时足够，但删除已部署节点时语义不清：如果直接物理删除记录，下一次生成远端配置时无法知道这个节点曾经存在，远端 sing-box 配置里可能继续保留旧节点；如果只禁用再删除，又依赖前端的隐藏规则和人工顺序，流程不够明确。

需要把节点部署状态调整为明确的三态：待部署、待删除、已部署，让节点新增、修改、禁用、删除和部署刷新形成一条可追踪的运维闭环。

## What Changes

- 将节点部署状态从“未部署 / 已部署”调整为“待部署 / 待删除 / 已部署”。
- 新增或修改节点、调整会影响远端配置的字段、启用/禁用节点、批量调整标签、还原部署版本时，将节点标记为“待部署”。
- 删除已部署节点时不立即物理删除，而是标记为“待删除”，并在下一次部署时从远端配置中排除该节点。
- 部署成功后：
  - 正常节点标记为“已部署”。
  - “待删除”节点在确认远端配置已刷新后物理删除，并同步清理标签关系和当前部署版本记录。
- 更新后台查询、订阅节点筛选、部署版本记录、前端筛选与表格展示，使三态状态在 API、服务和控制台中保持一致。

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `server-and-node-deployment`: 调整节点部署生命周期，支持待部署、待删除、已部署三态以及删除后的远端配置刷新流程。

## Impact

- 后端受影响范围：
  - `Node` 实体、`NodeDto`、`NodeConverter`、`NodeRepository`
  - `NodeService`、`NodeGroupService`
  - `NodeDeploymentService`、`CoreDeploymentExecutor`、`DeploymentDataLoader`
  - `NodeDeploymentVersionService`
  - `SubscriptionService`
  - `NodeController`
- 前端受影响范围：
  - `src/main/resources/META-INF/resources/static/js/vpn/node.js`
  - `src/main/resources/META-INF/resources/static/js/vpn/node-deploy-methods.js`
  - `src/main/resources/templates/vpn/node/filter-fields.html`
  - `src/main/resources/templates/vpn/node/table.html`
  - `src/main/resources/templates/vpn/node/modals.html`
- 数据影响：
  - 可复用现有 `node.deployed` 字段承载三态整数值，不必新增列。
  - 历史数据中 `0` 映射为“待部署”，`1` 映射为“已部署”。
  - 需要新增统一枚举或常量避免继续散落魔法数字。
