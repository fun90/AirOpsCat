# `NodeDeploymentService#switchNodeCore` 时序与流程图

> 历史废弃文档：当前主线已经移除内核变更功能，`switchNodeCore` 相关 API、DTO 和服务流程不再作为现行实现依据。本文仅保留用于理解旧版本迁移背景。

本文描述旧版本代码中 `com.fun90.airopscat.service.deployment.NodeDeploymentService#switchNodeCore` 的执行链路，基于以下历史实现整理：

- `src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java`
- `src/main/java/com/fun90/airopscat/service/deployment/CoreDeploymentExecutor.java`

## 1. 时序图

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client / Controller
    participant NDS as NodeDeploymentService
    participant NR as NodeRepository
    participant NGS as NodeGroupService
    participant NService as NodeService
    participant CMS as CoreManagementService
    participant DDL as DeploymentDataLoader
    participant CDE as CoreDeploymentExecutor
    participant SCR as ServerConfigRepository

    Client->>NDS: switchNodeCore(nodeIds, targetCoreType, redeploy)
    NDS->>NDS: 去空/去重 nodeIds
    NDS->>NDS: normalizeSupportedCoreType(targetCoreType)
    NDS->>NR: findByIdIn(uniqueNodeIds)
    NR-->>NDS: nodes
    NDS->>NDS: 校验节点存在、原内核唯一、目标内核不同
    NDS->>NGS: normalizeNodeGroup / findGroupNodes
    NGS-->>NDS: 同组节点
    NDS->>NDS: 校验同一节点组必须整体选择
    NDS->>NDS: 校验所选节点中不包含落地节点
    NDS->>NR: findByIdIn(outboundNodeIds)
    NR-->>NDS: outboundNodeMap

    loop 每个选中节点
        NDS->>NDS: collectSourceCores(node, sourceCoresByServerId)
        NDS->>NDS: validateNodeCoreSwitch(node, targetCoreType, outboundNodeMap)
        NDS->>NService: generateDefaultInbound(protocol, serverId, targetCoreType)
        NService-->>NDS: 默认 inbound 模板
        NDS->>NDS: translateInboundConfig(...)
        NDS->>NDS: 更新 node.coreType / node.inbound / node.deployed=0
        NDS->>NDS: 收集 affectedServerIds / switchedNodeIds
    end

    alt redeploy == true 且 affectedServerIds 非空
        loop 每台受影响服务器的原内核
            NDS->>CMS: executeOperations(coreType, STOP)
            CMS-->>NDS: stopResult
        end

        NDS->>NR: findByServerIdIn(affectedServerIds)
        NR-->>NDS: affectedNodes
        NDS->>NDS: deployNodesForcibly(affectedNodes)
        NDS->>NGS: expandWithRelatedGroups(affectedNodes)
        NGS-->>NDS: expandedAffectedNodes
        NDS->>DDL: load(expandedAffectedNodes)
        DDL-->>NDS: DeploymentPreload

        loop 每台 server
            NDS->>CDE: executeForServer(serverContext)
            CDE->>CDE: 按 coreType 分组 nodes

            loop 每个 coreType
                CDE->>CDE: build config
                CDE->>CMS: executeOperations(coreType, CONFIG + RESTART)
                CMS-->>CDE: deployment results
                CDE->>CDE: persist(execution)
                CDE->>SCR: findByServerIdAndConfigType(serverId, coreType)
                CDE->>SCR: persist(serverConfig)
                CDE->>SCR: findByServerId(serverId)
                CDE->>NR: countActiveByServerAssociationAndCoreType(serverId, configType)
                CDE->>CDE: reconcileServerConfigStatuses(serverId)
                CDE->>NR: persist(node.deployed=1)
            end
        end
        NDS-->>Client: NodeCoreSwitchResponse + deploymentResults
    else redeploy == false
        NDS-->>Client: NodeCoreSwitchResponse
    end
```

## 2. 流程图

```mermaid
flowchart TD
    A["开始: switchNodeCore(nodeIds, targetCoreType, redeploy)"] --> B["去空/去重 nodeIds"]
    B --> C{"nodeIds 为空?"}
    C -- 是 --> C1["抛出 IllegalArgumentException"]
    C -- 否 --> D["规范化目标内核: 仅允许 xray / sing-box"]
    D --> E["查询节点 findByIdIn"]
    E --> F{"节点数量匹配?"}
    F -- 否 --> F1["抛出 EntityNotFoundException"]
    F -- 是 --> G["校验同一节点组内的节点必须同时被选中"]
    G --> G1["校验所选节点原内核必须唯一"]
    G1 --> H{"目标内核与原内核相同?"}
    H -- 是 --> H1["抛出 IllegalArgumentException"]
    H -- 否 --> I{"包含落地节点?"}
    I -- 是 --> I1["抛出 IllegalArgumentException"]
    I -- 否 --> J["加载 outboundNodeMap"]
    J --> K["遍历每个节点"]
    K --> L["记录 sourceCoresByServerId"]
    L --> M["校验协议支持 / 出站节点内核一致"]
    M --> N["生成目标内核默认 inbound"]
    N --> O["翻译旧 inbound 到目标内核结构"]
    O --> P["更新节点: coreType / inbound / deployed=0"]
    P --> Q["收集 affectedServerIds / switchedNodeIds"]
    Q --> T{"redeploy == true 且有受影响服务器?"}
    T -- 否 --> U["返回 NodeCoreSwitchResponse"]
    T -- 是 --> V["按 sourceCoresByServerId 停止原内核"]
    V --> W["查询受影响服务器上的所有相关节点"]
    W --> X["deployNodesForcibly(affectedNodes)"]
    X --> Y["DeploymentDataLoader.load(...)"]
    Y --> Z["按服务器并发部署"]
    Z --> AA["CoreDeploymentExecutor.executeForServer(...)"]
    AA --> AB["同一服务器按 coreType 分组构建配置"]
    AB --> AC["执行 CONFIG + RESTART"]
    AC --> AD["persist(execution)"]
    AD --> AE["saveServerConfig(server, coreType, config)"]
    AE --> AF["reconcileServerConfigStatuses(serverId)"]
    AF --> AG["仅按节点数决定各 configType 是否 enabled"]
    AG --> AH["更新当前批次节点 deployed=1"]
    AH --> U
```

## 3. 关键节点说明

- 该方法不是只改 `Node.coreType`，还会同步翻译并覆盖 `Node.inbound`，同时把节点重新标记为 `deployed = 0`。
- 当前实现会先通过 `NodeGroupService` 校验所选节点，如果某个节点属于节点组，则必须整组一起切换。
- 历史实现曾显式禁止落地节点切换内核，避免影响把这些落地节点作为出站节点使用的代理节点。
- 历史实现不再在切换内核过程中联动更新路由规则，因为路由规则只依赖落地节点，而当时已经禁止落地节点切换内核。
- 如果 `redeploy = true`，重部署范围不是“仅本次切换的节点”，而是“所有受影响服务器上的相关节点”，并且会继续按节点组展开。
- 重部署时会先停原内核，再按服务器和 `coreType` 分组重新下发配置。
- `ServerConfig.enabled` 的最终状态不是在 `switchNodeCore` 中直接处理，而是在 `CoreDeploymentExecutor.saveServerConfig(...)` 后通过 `reconcileServerConfigStatuses(...)` 按节点数统一收敛。

## 4. 当前实现的简化伪代码

```text
switchNodeCore(nodeIds, targetCoreType, redeploy):
  1. 校验参数和目标内核
  2. 查询节点并校验:
     - 所有节点存在
     - 同组节点必须整体选择
     - 原内核唯一
     - 目标内核与原内核不同
  3. 遍历节点:
     - 校验协议和出站依赖
     - 翻译 inbound
     - 更新 coreType / inbound / deployed
     - 收集受影响服务器
  4. 如果 redeploy:
     - 停止原内核
     - 查询受影响服务器上的全部相关节点
     - 按节点组展开后执行强制重部署
  5. 返回响应
```
