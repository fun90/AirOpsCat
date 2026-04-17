# sing-box 单内核化与复杂度清理执行计划

## 背景

当前项目已经进入只支持 sing-box 内核的阶段，但代码结构仍保留了大量为 Xray、Hysteria2 独立内核和未来多内核扩展准备的抽象：

- 节点页面仍提供“切换内核”批量操作和弹窗。
- 后端仍保留 `/api/admin/nodes/switch-core` 接口、`NodeCoreSwitchRequest`、`NodeCoreSwitchResponse` 和 `NodeDeploymentService.switchNodeCore()` 流程。
- 部署流程仍按 `coreType` 分组，再通过 `CoreConfigBuilderRegistry`、`CoreManagementStrategyRegistry`、`DefaultInboundStrategyRegistry` 查找策略。
- `ProtocolType` 仍维护 `coreTypes` 支持矩阵，前端也按 `nodeType + coreType + protocol` 联动。
- 部分代码还把 `coreType` 当成可变维度处理，导致 sing-box 已是唯一内核后仍有多余判断、分支和 UI 状态。

本计划目标不是移除 sing-box 支持的协议能力。`vless`、`vless-reality`、`hysteria2`、`shadowtls`、`shadowsocks`、`socks` 等 sing-box 协议能力必须保留。

---

## 目标

1. **去除切换内核功能**
   - 删除前端“切换内核”入口、弹窗、状态和请求逻辑。
   - 删除后端切换内核 API、请求/响应 DTO 和服务流程。
   - 删除旧的切换内核流程文档，或标记为废弃并改为历史说明。

2. **降低单内核场景下的代码复杂度**
   - 将 sing-box 作为唯一内核常量和默认值。
   - 清理按内核类型分组、查注册表、判断不同内核路径的逻辑。
   - 将 sing-box 配置构建、默认入站生成、核心运维相关代码尽量收拢到明确的 sing-box 服务边界。
   - 保留数据库字段 `core_type`、DTO 字段 `coreType` 和订阅输出中的 `coreType`，作为兼容历史数据和前端展示的稳定字段，但业务层不再把它当成可切换维度。

---

## 非目标

- 不删除 sing-box 的 hysteria2 协议。
- 不改动节点协议模型本身，除非是去掉对 `coreType` 支持矩阵的依赖。
- 不做数据库字段删除迁移；`node.core_type`、`route_rule.core_type`、`server_config.config_type` 本阶段仍保留。
- 不重写订阅模板，只做必要的 `coreType == 'sing-box'` 判断简化评估。
- 不改变远程部署的最终行为：仍生成 `/etc/sing-box/config.json`，仍重启 sing-box。

---

## 第一部分：移除切换内核功能

### ✅ 任务 1：删除节点列表的“切换内核”入口

**文件**：

- `src/main/resources/templates/vpn/node/content.html`

删除批量操作下拉菜单中的“切换内核”菜单项：

- 删除 `openCoreSwitchModal()` 入口。
- 删除 `ti-cpu` 图标对应菜单项。
- 保留“批量部署”和“调整标签”。

验证：

- 节点页面批量操作中不再出现“切换内核”。
- 选择节点后批量部署、调整标签仍可用。

---

### ✅ 任务 2：删除切换内核弹窗模板

**文件**：

- `src/main/resources/templates/vpn/node/modals.html`

删除整个 `#node-coreSwitchModal` 弹窗块：

- 目标内核下拉框。
- 切换后重部署开关。
- “确认切换”按钮。
- 相关提示文案。

同时修改编辑节点弹窗中的内核提示：

- 当前提示是“节点不允许通过编辑修改内核类型，请使用切换内核功能。”
- 改为“当前系统仅支持 sing-box 内核，内核类型不可修改。”

验证：

- 页面 DOM 中不再有 `node-coreSwitchModal`。
- 编辑节点时不再提示使用切换内核。

---

### ✅ 任务 3：删除前端切换内核状态和方法

**文件**：

- `src/main/resources/META-INF/resources/static/js/vpn/node.js`
- `src/main/resources/META-INF/resources/static/js/vpn/node-deploy-methods.js`

删除以下状态：

- `coreSwitchModal`
- `switchingCore`
- `coreSwitchTarget`
- `coreSwitchRedeploy`

删除以下方法：

- `openCoreSwitchModal()`
- `switchSelectedNodesCore()`

删除对 `/api/admin/nodes/switch-core` 的 fetch 调用。

验证：

- `rg "coreSwitch|switchingCore|switch-core|openCoreSwitchModal|switchSelectedNodesCore" src/main/resources/META-INF/resources/static/js src/main/resources/templates` 无业务引用。
- 节点部署相关 JS 仍能正常加载。

---

### ✅ 任务 4：删除后端切换内核 API

**文件**：

- `src/main/java/com/fun90/airopscat/controller/NodeController.java`

删除：

- `NodeCoreSwitchRequest` import。
- `NodeCoreSwitchResponse` import。
- `@POST @Path("/switch-core") switchNodeCore(...)` 方法。

验证：

- `rg "switch-core|NodeCoreSwitch" src/main/java/com/fun90/airopscat/controller` 无结果。
- 节点部署、批量标签、还原版本接口不受影响。

---

### ✅ 任务 5：删除切换内核 DTO

删除：

- `src/main/java/com/fun90/airopscat/model/dto/NodeCoreSwitchRequest.java`
- `src/main/java/com/fun90/airopscat/model/dto/NodeCoreSwitchResponse.java`

同步检查：

- `JsonReflectionConfiguration` 中如有这两个 DTO 的反射注册，移除。
- 其他测试或文档引用按需清理。

验证：

- `rg "NodeCoreSwitch" src/main/java` 无结果。

---

### ✅ 任务 6：删除 `NodeDeploymentService` 中的切换内核流程

**文件**：

- `src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java`

删除以下方法和仅为它们服务的依赖：

- `switchNodeCore(...)`
- `validateNoLandingNodesForCoreSwitch(...)`
- `validateAssociationGroupSelection(...)`（若只被切换内核使用）
- `normalizeSupportedCoreType(...)`（若只被切换内核使用）
- `validateNodeCoreSwitch(...)`
- `loadOutboundNodeMap(...)`
- `collectNodeServers(...)`
- `collectSourceCores(...)`
- `stopSourceCoresBeforeRedeploy(...)`
- `translateInboundConfig(...)` 及其相关 helper（需先确认调用方）

同步清理可能变成未使用的注入项和 import：

- `NodeService`
- `CoreManagementService`
- `CoreConfigBuilderRegistry`
- `SshConnectionService`
- `CoreOperation`
- `CoreType`
- `NodeType`
- `ProtocolType`
- `DefaultConfigDto`
- `CoreManagementResult`
- `SshConfig`

验证：

- `NodeDeploymentService` 只保留部署、强制部署、关联组部署、预览配置和版本相关逻辑。
- `./mvnw -DskipTests compile` 通过。

---

### 任务 7：处理切换内核历史文档

**文件**：

- `docs/switch-node-core-flow.md`
- `docs/node-associated-design.md`
- `docs/node-management-codex-guide.md`
- `docs/node-management-codex-from-scratch.md`

处理方式二选一：

1. 删除 `docs/switch-node-core-flow.md`，并从其他文档中移除“切换内核”作为当前功能的描述。
2. 保留 `docs/switch-node-core-flow.md`，但在文件顶部加“历史文档，当前功能已移除”的警告，并从当前功能文档中移除引用。

建议采用第 2 种，便于后续追溯为什么移除。

验证：

- 当前功能文档不再指导实现或使用切换内核。

---

## 第二部分：单内核化默认值与校验

### ✅ 任务 8：统一 sing-box 内核常量

新增或复用一个单一来源：

- 方案 A：保留 `CoreType.SING_BOX`，所有默认值和校验从这里取值。
- 方案 B：新增 `CoreConstants.SING_BOX = "sing-box"`，逐步减少 enum 在业务层的存在感。

建议采用方案 A，改动较小。

需要统一的默认值位置：

- `NodeController.getDefaultInbound()` 的 `@DefaultValue("xray")` 改为 `sing-box`，或直接去掉 `coreType` 参数。
- `NodeConverter.fromRequest()` 中空值回填。
- `NodeService.saveNode()` 中 coreType 规范化。
- `DataInitializationConfig.initializeNodeCoreType()`。
- 前端 `node.js`、`node-form-methods.js` 默认 `coreType`。

验证：

- `rg "\"xray\"|DefaultValue\\(\"xray\"\\)|'xray'" src/main/java src/main/resources/META-INF/resources/static/js` 不再命中当前业务代码。

---

### 任务 9：把 `coreType` 从“用户可选项”降级为固定展示值

**后端**：

- `NodeService.getNodeCoreTypeOptions()` 可继续返回单个 sing-box 选项，供现有前端兼容。
- 新增内部 helper：`requireSingBoxCoreType(String coreType)`，用于兼容请求中传入空值或 sing-box。
- 对传入非 sing-box 的请求，统一返回明确错误：“当前仅支持 sing-box 内核”。

**前端**：

- 创建节点表单中不再用下拉框选择内核，改为固定只读显示或隐藏字段。
- 编辑节点表单中保留只读展示即可，不再有“切换内核”引导。
- 筛选里的“内核”可考虑删除；若保留，只显示 sing-box。

验证：

- 创建/编辑节点时无需选择内核也能默认使用 sing-box。
- 老请求传入 `coreType = sing-box` 正常，传入其他值失败。

---

### ✅ 任务 10：简化 `ProtocolType` 的支持矩阵

**文件**：

- `src/main/java/com/fun90/airopscat/model/enums/ProtocolType.java`

当前 `ProtocolType` 维护 `List<String> coreTypes`，现在可以简化为只按 `nodeType` 判断：

- 删除 `coreTypes` 字段和构造参数。
- `supports(Integer nodeType, String coreType)` 改为只校验 `nodeType`，或新增 `supportsNodeType(Integer nodeType)`。
- `isSupported(protocol, nodeType, coreType)` 可暂时保留签名兼容，但忽略 `coreType` 或只校验其为 sing-box。
- `getSupportedProtocols(nodeType, coreType)` 可暂时保留签名兼容，内部只按 `nodeType` 返回。

前端同步：

- `/api/admin/nodes/protocols` 返回值不再需要 `coreTypes`。
- `node.js`、`node-form-methods.js` 中按 `protocol.coreTypes.includes(coreType)` 的逻辑改为只按 `type` 过滤。

验证：

- 创建代理节点仍显示 vless、vless-reality、hysteria2、shadowtls。
- 创建落地节点仍显示 shadowsocks、socks。
- hysteria2 协议仍可生成默认入站配置。

---

## 第三部分：收拢 sing-box 配置与部署逻辑

### ✅ 任务 11：移除部署流程中的按内核分组

**文件**：

- `src/main/java/com/fun90/airopscat/service/deployment/CoreDeploymentExecutor.java`
- `src/main/java/com/fun90/airopscat/model/dto/deployment/CoreDeploymentExecution.java`

当前流程：

- `executeForServer()` 按 `node.coreType` 分组。
- 每组调用 `coreConfigBuilderRegistry.getStrategy(coreType)`。
- 每组分别保存 `ServerConfig`。

目标流程：

- 每台服务器只构建一次 sing-box 配置。
- `CoreDeploymentExecution` 可以保留 `coreType` 字段为 `sing-box`，也可以重命名为 `DeploymentExecution`（建议第一轮先保留字段，减少级联改动）。
- `buildConfig(ctx, nodes)` 直接调用 sing-box 构建器。
- `deployToServer(connection, server, config)` 直接执行 sing-box `CONFIG + RESTART`。
- `logServerDeploymentSummary` 不再记录 `coreCount`。

验证：

- 单服务器部署只产生一份 `/etc/sing-box/config.json`。
- `server_config.config_type` 仍保存为 `sing-box`。
- 多节点、多协议部署结果不变。

---

### ✅ 任务 12：简化 `DeploymentDataLoader` 的 coreType 过滤

**文件**：

- `src/main/java/com/fun90/airopscat/service/deployment/DeploymentDataLoader.java`

删除或弱化以下多内核逻辑：

- `CORE_TYPE_XRAY`
- `collectTargetCoreTypesByServerId(...)`
- `filterRelatedNodesByServerCore(...)`
- `matchesTargetServerCore(...)`
- `supportsManagedClients(...)` 中的 xray 判断

目标：

- 加载目标服务器相关节点时，不再按不同核心切片。
- route rules 默认都是 sing-box，不需要把 route rule 的 `coreType` 作为部署切片依据。
- `supportsManagedClients()` 只认 sing-box，或如果只用于 vless 用户合并，则改名为 `supportsManagedClientsInSingBox(...)`。

验证：

- 部署某服务器时仍能带上关联出站节点、路由规则依赖节点、节点组相关节点。
- 不再出现 xray 常量。

---

### ✅ 任务 13：把配置构建注册表替换为直接依赖 sing-box 构建器

**文件**：

- `src/main/java/com/fun90/airopscat/service/deployment/CoreDeploymentExecutor.java`
- `src/main/java/com/fun90/airopscat/service/deployment/SingBoxConfigBuilder.java`
- `src/main/java/com/fun90/airopscat/service/deployment/registry/CoreConfigBuilderRegistry.java`
- `src/main/java/com/fun90/airopscat/service/deployment/strategy/CoreConfigBuilder.java`
- `src/main/java/com/fun90/airopscat/annotation/SupportedCores.java`

建议分两步：

1. 第一轮：`CoreDeploymentExecutor` 直接注入 `SingBoxConfigBuilder`，不再使用 `CoreConfigBuilderRegistry`。
2. 第二轮：确认无引用后删除 `CoreConfigBuilderRegistry`、`CoreConfigBuilder` 和 `@SupportedCores` 在配置构建侧的用途。

注意：

- `@SupportedCores` 也被核心管理和默认入站策略注册表使用；只有在后续任务替换所有注册表后才能删除注解。

验证：

- `rg "CoreConfigBuilderRegistry|CoreConfigBuilder" src/main/java` 无业务引用，或只剩待删除文件。

---

### ✅ 任务 14：把默认入站策略注册表替换为直接依赖 sing-box 默认入站生成器

**文件**：

- `src/main/java/com/fun90/airopscat/service/NodeService.java`
- `src/main/java/com/fun90/airopscat/singbox/SingBoxDefaultInboundFactory.java`
- `src/main/java/com/fun90/airopscat/service/inbound/registry/DefaultInboundStrategyRegistry.java`
- `src/main/java/com/fun90/airopscat/service/inbound/strategy/DefaultInboundStrategy.java`

目标：

- `NodeService.generateDefaultInbound(protocol, serverId, accessHostId, coreType)` 改为直接调用 `SingBoxDefaultInboundFactory.generateDefaultInbound(...)`。
- 可保留方法签名中的 `coreType` 用于 API 兼容，但只接受空值或 sing-box。
- 删除 `DefaultInboundStrategyRegistry` 和 `DefaultInboundStrategy`，并将原 sing-box 默认入站策略改名为 `SingBoxDefaultInboundFactory`。

验证：

- `/api/admin/nodes/default-inbound` 对所有 sing-box 协议仍返回正确模板。
- Reality keypair 生成逻辑仍使用 sing-box。

---

### ✅ 任务 15：把核心运维注册表替换为直接依赖 sing-box 运维服务

**文件**：

- `src/main/java/com/fun90/airopscat/service/core/CoreManagementService.java`
- `src/main/java/com/fun90/airopscat/singbox/SingBoxCoreManager.java`
- `src/main/java/com/fun90/airopscat/service/core/registry/CoreManagementStrategyRegistry.java`
- `src/main/java/com/fun90/airopscat/service/core/strategy/CoreManagementStrategy.java`

目标：

- `CoreManagementService.executeOperations(coreType, ...)` 可保留兼容签名，但内部只接受空值或 sing-box，然后直接调用 `SingBoxCoreManager`。
- 将原 `SingBoxCoreManagementStrategy` 改名为 `SingBoxCoreManager`，删除策略接口和注册表。
- 删除对 `@SupportedCores` 的最后依赖后，再删除注解和 `AbstractStrategyRegistry` 中仅为内核策略服务的使用点；注意不要影响 DNS 注册表等其他注册表。

验证：

- 远程安装、配置上传、重启、状态检查行为不变。
- `rg "CoreManagementStrategyRegistry|CoreManagementStrategy" src/main/java` 无业务引用，或只剩待删除文件。

---

### 任务 16：固定 server_config 路径与启用状态逻辑

**文件**：

- `src/main/java/com/fun90/airopscat/service/deployment/CoreDeploymentExecutor.java`
- `src/main/java/com/fun90/airopscat/service/ServerConfigService.java`
- `src/main/java/com/fun90/airopscat/controller/ServerConfigController.java`

清理：

- `newServerConfig()` 中的 hysteria/xray fallback 路径，固定为 `/etc/sing-box/config.json`。
- `reconcileServerConfigStatuses()` 不再按不同 `configType` 判断启用状态，或只判断 sing-box 使用。
- `ServerConfigController.getConfigTypes()` 可返回单个 sing-box，或保留从 `CoreType.values()` 生成但实际只有 sing-box。
- 统计卡片只保留总数、启用、禁用、sing-box。

验证：

- 配置快照仍能创建和更新。
- 服务器配置页面不出现非 sing-box 类型。

---

## 第四部分：前端单内核化清理

### ✅ 任务 17：简化节点表单的内核字段

**文件**：

- `src/main/resources/templates/vpn/node/modals.html`
- `src/main/resources/META-INF/resources/static/js/vpn/node-form-methods.js`
- `src/main/resources/META-INF/resources/static/js/vpn/node.js`

目标：

- 新增节点时不需要用户选择内核，默认 `newItem.coreType = 'sing-box'`。
- 编辑节点时只展示 `sing-box`，或完全隐藏内核字段。
- `onCoreTypeChange()`、`onEditCoreTypeChange()` 如只为多内核服务，可删除或改为内部刷新协议列表。
- 表单校验不再提示“请选择内核类型”，只在提交前保证 payload 为 sing-box。

验证：

- 新建节点流程更短。
- 切换协议仍能刷新默认入站配置。

---

### ✅ 任务 18：简化协议过滤逻辑

**文件**：

- `src/main/resources/META-INF/resources/static/js/vpn/node.js`
- `src/main/resources/META-INF/resources/static/js/vpn/node-form-methods.js`

目标：

- `getAvailableProtocols(item)` 只根据 `item.type` 过滤。
- 删除 `protocol.coreTypes.includes(coreType)` 判断。
- 默认入站请求可以不再拼 `coreType`，或固定传 `sing-box`。

验证：

- 代理节点协议列表包含 sing-box 支持的代理协议。
- 落地节点协议列表包含落地协议。

---

### ✅ 任务 19：评估并简化筛选、表格、预览中的 coreType 展示

**文件**：

- `src/main/resources/templates/vpn/node/filter-fields.html`
- `src/main/resources/templates/vpn/node/table.html`
- `src/main/resources/templates/vpn/route-rule/filters.html`
- `src/main/resources/templates/vpn/route-rule/table.html`
- `src/main/resources/templates/device/server/modals.html`
- `src/main/resources/META-INF/resources/static/js/device/server.js`

建议：

- 节点筛选中的“内核”可以删除，因为只有 sing-box。
- 节点表格可从 `coreType / protocol` 改为只突出显示 `protocol`，必要时在详情中保留 `coreType`。
- 路由规则筛选中的“内核”可以删除或固定为 sing-box。
- 服务器配置预览不再需要 coreType tabs，直接显示唯一配置。

验证：

- 页面信息密度降低，但仍能看到协议和部署状态。
- 服务器配置预览仍可查看 JSON。

---

## 第五部分：RouteRule、NodeGroup 与仓储层清理

### ✅ 任务 20：简化路由规则 coreType 处理

**文件**：

- `src/main/java/com/fun90/airopscat/service/RouteRuleService.java`
- `src/main/java/com/fun90/airopscat/controller/RouteRuleController.java`
- `src/main/java/com/fun90/airopscat/model/dto/RouteRuleRequest.java`
- `src/main/java/com/fun90/airopscat/model/dto/RouteRuleDto.java`

目标：

- 新建/编辑路由规则时默认写入 sing-box。
- 查询接口可以继续接受 `coreType`，但只作为兼容参数。
- 落地节点选择接口不再需要前端传 coreType，后端固定筛选 sing-box 或直接按落地节点类型筛选。
- 页面上不再要求用户选择内核。

验证：

- 路由规则仍能生成 sing-box route 配置。
- 旧数据中非 sing-box 路由规则不参与部署，可通过数据库迁移清理。

---

### ✅ 任务 21：简化节点组的 coreType 兼容判断

**文件**：

- `src/main/java/com/fun90/airopscat/service/NodeGroupService.java`
- `src/main/java/com/fun90/airopscat/repository/NodeRepository.java`

目标：

- 节点组兼容性不再比较多内核，只比较节点类型、服务器冲突和组名。
- `findNodeGroupCandidateNodes(type, coreType, excludeId)` 可改为 `findNodeGroupCandidateNodes(type, excludeId)`。
- 前端 group-options/group-config 请求不再需要传 coreType，或后端忽略该参数。

验证：

- 节点组选择和自动带出配置仍可用。
- 同组节点仍避免部署到同一服务器的约束不变。

---

### ✅ 任务 22：清理仓储层按 coreType 统计/计数的冗余方法

**文件**：

- `src/main/java/com/fun90/airopscat/repository/NodeRepository.java`
- `src/main/java/com/fun90/airopscat/repository/RouteRuleRepository.java`
- `src/main/java/com/fun90/airopscat/repository/ServerConfigRepository.java`

评估并删除或收敛：

- `countByServerAssociationAndCoreType(...)`
- `countActiveByServerAssociationAndCoreType(...)`
- `countByCoreType(...)`
- 其他只为多内核统计服务的方法。

保留：

- 兼容历史数据迁移需要的查询。
- 页面统计仍实际使用的方法。

验证：

- `rg "count.*CoreType|ByCoreType|coreType\\)" src/main/java/com/fun90/airopscat/repository src/main/java/com/fun90/airopscat/service` 无无用引用。

---

## 第六部分：数据迁移与兼容

### 任务 23：新增一次性数据清理 SQL

建议新增文档：

- `docs/sing-box-only-data-migration.md`

内容：

```sql
-- 执行前必须备份数据库

UPDATE node
SET core_type = 'sing-box'
WHERE core_type IS NULL OR trim(core_type) = '' OR lower(trim(core_type)) <> 'sing-box';

UPDATE route_rule
SET core_type = 'sing-box'
WHERE core_type IS NULL OR trim(core_type) = '' OR lower(trim(core_type)) <> 'sing-box';

UPDATE server_config
SET config_type = 'sing-box',
    path = '/etc/sing-box/config.json'
WHERE config_type IS NULL OR trim(config_type) = '' OR lower(trim(config_type)) <> 'sing-box';
```

注意：

- 如果历史 Xray/Hysteria2 独立内核配置无法兼容，应先人工确认节点协议和 inbound JSON 是否可被 sing-box 使用。
- 对无法迁移的历史配置快照，可以删除后重新部署生成。

---

### 任务 24：启动时兜底迁移保持轻量

**文件**：

- `src/main/java/com/fun90/airopscat/config/DataInitializationConfig.java`

保留：

- 空 `node.core_type` 回填 sing-box。

评估是否新增：

- 空 `route_rule.core_type` 回填 sing-box。
- 空 `server_config.config_type` 回填 sing-box。

不建议在启动时自动覆盖非 sing-box 历史值，避免未经确认破坏旧数据；生产迁移用任务 23 的 SQL 手动执行。

---

## 第七部分：文档与测试

### ✅ 任务 25：更新当前功能文档

重点更新：

- `CLAUDE.md` 中“协议/核心支持”描述：改为 sing-box 内核，支持多种 sing-box 协议。
- `docs/how-to-add-console-module.md` 中示例“sing-box / xray 模板”描述。
- 节点管理相关文档中“切换内核”改为历史能力或删除。

验证：

- `rg "切换内核|xray / sing-box|sing-box / xray|xray、sing-box|xray/sing-box" CLAUDE.md docs` 只剩历史文档或迁移说明。

---

### 任务 26：补充回归测试清单

至少手动验证：

1. 新建 sing-box vless 节点。
2. 新建 sing-box hysteria2 节点。
3. 新建落地 shadowsocks/socks 节点。
4. 编辑节点协议后默认入站配置刷新。
5. 节点组选择和配置继承。
6. 批量部署。
7. 服务器配置预览。
8. 路由规则创建并参与 sing-box route 生成。
9. 订阅模板输出 hysteria2 节点。
10. 账号变更触发部署。

自动验证：

```bash
./mvnw -DskipTests compile
./mvnw test
```

如果测试覆盖不足，至少新增服务层小测试覆盖：

- `ProtocolType` 单内核协议过滤。
- `NodeService.generateDefaultInbound()` 对 hysteria2 协议的默认配置生成。
- `SingBoxConfigBuilder` 对多协议节点构建配置。

---

## 建议执行顺序

```text
[阶段 1] 移除切换内核功能
  任务 1  删除前端入口
  任务 2  删除弹窗模板
  任务 3  删除前端状态和请求
  任务 4  删除后端 API
  任务 5  删除 DTO
  任务 6  删除服务层切换流程
  任务 7  处理历史文档
  → 编译验证

[阶段 2] 统一 sing-box 默认值
  任务 8  统一 sing-box 常量和默认值
  任务 9  coreType 降级为固定展示值
  任务 10 简化 ProtocolType 支持矩阵
  → 编译验证 + 节点表单手动验证

[阶段 3] 收拢部署与配置构建
  任务 11 移除按内核分组部署
  任务 12 简化 DeploymentDataLoader
  任务 13 直接依赖 SingBoxConfigBuilder
  任务 14 直接依赖 SingBoxDefaultInboundFactory
  任务 15 直接依赖 SingBoxCoreManager
  任务 16 固定 server_config 路径与状态逻辑
  → 编译验证 + 部署预览验证

[阶段 4] 前端和业务周边清理
  任务 17 简化节点表单内核字段
  任务 18 简化协议过滤逻辑
  ✅ 任务 19 简化筛选、表格、预览 coreType 展示
  ✅ 任务 20 简化路由规则 coreType 处理
  ✅ 任务 21 简化节点组 coreType 兼容判断
  ✅ 任务 22 清理仓储层冗余方法
  → 编译验证 + 主要页面手动验证

[阶段 5] 迁移、文档、测试
  任务 23 新增数据清理 SQL
  任务 24 启动兜底迁移评估
  ✅ 任务 25 更新当前功能文档
  任务 26 执行回归测试清单
```

---

## 风险点

1. **历史数据兼容**：数据库中可能仍有 `core_type != 'sing-box'` 的节点、路由规则或配置快照，直接部署可能失败。执行代码清理前应先确认迁移策略。

2. **订阅模板依赖 `coreType`**：部分订阅模板使用 `node.coreType == 'sing-box'` 判断 hysteria2 输出，字段保留期间风险较低；若后续删除字段，需要同步改模板。

3. **节点组与出站链路**：旧逻辑用 coreType 保证关联节点在同一内核下可组合。单内核后可以简化，但仍必须保留节点类型、服务器冲突、出站节点存在性等校验。

4. **注册表删除顺序**：`@SupportedCores` 和 `AbstractStrategyRegistry` 可能仍被多个策略体系使用，不能一次性删除。必须先替换所有内核相关注册表引用，再清理公共注解或抽象类。

5. **部署行为回归**：按内核分组删除后，每台服务器只生成一次配置。需要重点验证包含代理节点、落地节点、路由规则、出站链路和节点组的复杂服务器。

6. **前端缓存状态**：删除切换内核状态后，要检查 petite-vue 初始化对象中是否仍引用已删除字段，否则页面运行时可能报错。
