# AirOpsCat 节点组现状说明

## 1. 模型定义

AirOpsCat 当前使用“节点组”来表示节点之间的配置组关系。

节点通过 `nodeGroup` 字段归组：

- `nodeGroup` 为空：节点不属于任何组
- `nodeGroup` 有值：所有同名节点视为同一个节点组

节点组是单值归属关系，一个节点同一时间只能属于一个节点组。

---

## 2. 节点组约束

节点加入已有节点组时，组内节点必须满足：

- 相同节点类型
- 相同内核类型
- 不在同一服务器上

如果节点组中已经存在节点，则当前节点会在保存时自动更新为组内已有配置：

- 协议
- 端口
- 入站配置
- 出站配置

如果当前节点是该节点组的第一个节点，则以当前节点配置作为该组的初始配置。

---

## 3. 数据模型

节点组直接存储在 `node.node_group` 字段中，不再使用单独关系表。

代码位置：

- 实体：[Node.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/model/entity/Node.java)
- 仓库：[NodeRepository.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/repository/NodeRepository.java)
- 节点服务：[NodeService.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/service/NodeService.java)
- 节点组规则服务：[NodeGroupService.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/service/NodeGroupService.java)

---

## 4. 接口与 DTO

节点创建、编辑通过 `nodeGroup` 字段提交节点组。

代码位置：

- [NodeRequest.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/model/dto/NodeRequest.java)

节点列表和详情返回 `nodeGroup`。

代码位置：

- [NodeDto.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/model/dto/NodeDto.java)
- [NodeConverter.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/model/convert/NodeConverter.java)

节点组候选接口：

- `GET /api/admin/nodes/group-options`

节点组配置查询接口：

- `GET /api/admin/nodes/group-config`

接口会按以下条件返回候选组名：

- 相同 `type`
- 相同 `coreType`
- 当前服务器不在该组中
- 支持关键字搜索
- 默认返回前 10 个候选项

---

## 5. 保存规则

创建或编辑节点时：

1. 归一化 `nodeGroup`，空字符串按未分组处理。
2. 如果目标节点组已存在，则校验组内节点类型、内核、服务器约束。
3. 校验当前节点端口占用。
4. 保存当前节点。
5. 如果目标节点组已存在，则当前节点会自动对齐为组内现有配置。
6. 组变更后，同组其他节点会被标记为 `deployed = 0`。

如果输入的节点组已存在，则当前节点加入该组，并自动对齐为组内现有配置。  
如果输入的节点组不存在，则当前节点以该组名创建新组，并以当前配置作为该组初始配置。

---

## 6. 部署规则

部署时按 `nodeGroup` 扩展目标节点：

- 单节点部署会扩展到同组全部节点
- 批量部署会扩展到各自所在组
- 强制部署同样按组展开

切换内核时，如果某节点属于节点组，则必须同时选择该组所有节点。

代码位置：

- [NodeDeploymentService.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java)

---

## 7. 用户信息合并

部署快照生成时，系统会按节点组聚合同组节点的有效用户集合，并把合并结果写回组内每个节点的部署快照。

协议对应的合并位置：

- `vless` / `vless-reality`：`settings.clients`
- `hysteria2`：`users`
- `socks`：`users`
- `shadowsocks`：`users`

代码位置：

- [DeploymentDataLoader.java](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/java/com/fun90/airopscat/service/deployment/DeploymentDataLoader.java)

---

## 8. 部署触发方式

当前代码没有启用节点标签变化、账户标签变化、账户启用/禁用/续期后的自动部署。

这些操作只更新数据，不自动下发节点部署。

当前主要部署入口：

- 单节点部署
- 批量部署
- 强制部署

---

## 9. 前端交互

节点组使用 Tom Select 单选输入组件。

交互方式：

- 组件形态为 input
- 默认展示 10 个候选组名
- 只能单选
- 支持直接输入新组名
- 输入组名存在则加入该组
- 输入组名不存在则以该名称创建新组
- 只有命中已有节点组并继承组配置时，协议 / 端口 / 入站 / 出站才会被锁定

代码位置：

- [node.js](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/node.js)
- [node-form-methods.js](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/node-form-methods.js)
- [node-deploy-methods.js](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/resources/META-INF/resources/static/js/vpn/node-deploy-methods.js)
- [modals.html](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/resources/templates/vpn/node/modals.html)
- [table.html](/Users/omg/Documents/Code/VPN/AirOpsCat/src/main/resources/templates/vpn/node/table.html)

---

## 10. 回归重点

1. 节点组候选只返回相同类型、相同内核、且当前服务器不在组内的组名。
2. 节点组组件只能单选，且允许手动输入。
3. 输入已有组名时，当前节点会自动更新为组内现有配置后再加入该组。
4. 输入新组名时，节点正确创建并加入新组。
5. 新组的第一个节点会以当前配置作为组配置。
6. 部署任一节点时，会按节点组扩展部署目标。
7. 单节点部署返回结果需要能反映整组部署汇总，而不是只返回首条结果。
8. 部署时会按节点组合并用户信息。
9. 切换内核时，同组节点必须整组一起选择。
10. 标签、账户启停、续期变化不会自动触发部署。
