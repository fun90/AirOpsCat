# AirOpsCat 节点管理模块 Codex 实施指南

## 1. 文档目标

这份文档回答一个具体问题：

如果从 0 开始，由我来使用 Codex 在 AirOpsCat 里完整接手“节点管理”功能模块，我会怎么做。

目标包含两部分：

1. 先把当前仓库里的节点管理模块分析清楚。
2. 再把使用 Codex 的方法沉淀成可复用文档，包含提示词、SKILL、MCP 设计建议。

当前分析入口：

- `controller.NodeController`
- `templates/vpn/node/*`
- `static/js/vpn/node.js`
- `static/js/vpn/node-form-methods.js`
- `static/js/vpn/node-deploy-methods.js`

---

## 2. 当前模块的真实边界

“节点管理”不是单纯 CRUD，它是一个跨 4 层的业务模块：

1. 页面层
2. API 层
3. 业务与部署层
4. 协议与配置模板层

从现有代码看，节点管理已经覆盖这些能力：

- 节点分页查询、搜索、组合筛选
- 新增节点、编辑节点、删除节点
- 启用 / 禁用节点
- 获取服务器列表、落地节点列表、可用端口
- 根据 `coreType + protocol` 生成默认 inbound 配置
- 节点组候选查询、组配置继承与锁定
- 校验当前服务器端口冲突和节点组服务器冲突
- 节点复制
- 单节点部署、强制重部署、批量部署
- xray / sing-box 内核切换
- 切换内核时翻译 inbound 配置
- 切换内核时联动更新路由规则 `coreType`

这意味着 Codex 要处理的不是一个页面，而是一条完整业务链路。

---

## 3. 代码结构速览

### 3.1 入口与页面

- API 控制器：`src/main/java/com/fun90/airopscat/controller/NodeController.java`
- 菜单入口：`/vpn/node`
- 页面模板碎片：
  - `src/main/resources/templates/vpn/node/content.html`
  - `src/main/resources/templates/vpn/node/toolbar.html`
  - `src/main/resources/templates/vpn/node/stats.html`
  - `src/main/resources/templates/vpn/node/table.html`
  - `src/main/resources/templates/vpn/node/filters.html`
  - `src/main/resources/templates/vpn/node/mobile-filter-drawer.html`
  - `src/main/resources/templates/vpn/node/modals.html`
- 前端脚本：
  - `src/main/resources/META-INF/resources/static/js/vpn/node.js`
  - `src/main/resources/META-INF/resources/static/js/vpn/node-form-methods.js`
  - `src/main/resources/META-INF/resources/static/js/vpn/node-deploy-methods.js`
  - 复用基类：`src/main/resources/META-INF/resources/static/js/common/data-table.js`

### 3.2 后端核心

- 业务服务：`src/main/java/com/fun90/airopscat/service/NodeService.java`
- 节点组规则服务：`src/main/java/com/fun90/airopscat/service/NodeGroupService.java`
- 部署服务：`src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java`
- 数据模型：
  - `src/main/java/com/fun90/airopscat/model/entity/Node.java`
  - `src/main/java/com/fun90/airopscat/model/dto/NodeRequest.java`
  - `src/main/java/com/fun90/airopscat/model/dto/NodeDto.java`
  - `src/main/java/com/fun90/airopscat/model/convert/NodeConverter.java`
- 枚举：
  - `src/main/java/com/fun90/airopscat/model/enums/NodeType.java`
  - `src/main/java/com/fun90/airopscat/model/enums/CoreType.java`
  - `src/main/java/com/fun90/airopscat/model/enums/ProtocolType.java`

### 3.3 协议策略与模板

- 默认 inbound 策略注册：
  - `src/main/java/com/fun90/airopscat/service/inbound/registry/DefaultInboundStrategyRegistry.java`
- 默认 inbound 策略实现：
  - `src/main/java/com/fun90/airopscat/service/inbound/strategy/impl/XrayDefaultInboundStrategy.java`
  - `src/main/java/com/fun90/airopscat/service/inbound/strategy/impl/SingBoxDefaultInboundStrategy.java`
- 配置模板目录：
  - `src/main/resources/config/core`

---

## 4. 节点管理模块的业务模型

### 4.1 Node 核心字段

`Node` 实体里，真正决定节点行为的字段是：

- `serverId`：主服务器
- `nodeGroup`：节点组名称
- `port`：节点端口
- `protocol`：协议
- `coreType`：内核类型，当前主线是 `xray` / `sing-box`
- `type`：节点类型
  - `0` = 代理节点
  - `1` = 落地节点
- `inbound`：JSON 字符串，存放协议入站配置
- `outId`：出站节点引用
- `rule`：JSON 字符串，存放节点路由规则
- `level`：级别
- `deployed`：部署状态
- `disabled`：启用状态
- `name` + `no`：业务标识

### 4.2 前端页面反映出的业务规则

从 `node.js` 和 `templates/vpn/node/modals.html` 可以提炼出这些产品规则：

- 节点类型变化会影响协议可选项。
- 内核类型变化会影响协议可选项。
- 创建时允许直接选择 `coreType`。
- 编辑时禁止直接修改 `coreType`，必须通过“切换内核”功能。
- 代理节点可以选择落地节点作为 `outId`。
- 支持节点组，加入已有组时会继承组内已有节点的协议、端口、入站、出站配置。
- 节点组只能包含相同节点类型、相同内核类型、且不在同一服务器上的节点。
- 输入一个新的节点组名时，当前节点作为组首节点保留可编辑配置。
- 部署任一组内节点时会联动部署同组节点，并把组内有效用户集合合并到每个节点的部署快照里。
- inbound / rule 允许用户直接编辑 JSON。
- 节点复制时需要重新分配端口，避免冲突。
- 禁用节点、编辑关键字段、切换内核后，节点会回到“未部署”状态。

---

## 5. API 与页面动作映射

### 5.1 页面动作

`static/js/vpn/node.js` 当前驱动了这些动作：

- 初始化加载：
  - 服务器列表
  - 落地节点列表
  - 节点类型
  - 内核类型
  - 协议类型
  - 可用标签
- 表格查询：
  - 关键词搜索
  - 服务器、类型、内核、协议、启用状态、部署状态筛选
- 表单动作：
  - 创建节点
  - 编辑节点
  - 删除节点
  - 启用 / 禁用
  - 获取可用端口
  - 校验端口占用
  - 根据协议重新生成默认 inbound
  - 查看配置
  - 复制节点
  - 批量部署
  - 批量切换内核
  - 单节点部署 / 强制部署

### 5.2 对应 API

`NodeController` 暴露出的节点模块 API：

- `GET /api/admin/nodes`
- `GET /api/admin/nodes/{id}`
- `GET /api/admin/nodes/server/{serverId}`
- `GET /api/admin/nodes/landing`
- `GET /api/admin/nodes/types`
- `GET /api/admin/nodes/core-types`
- `GET /api/admin/nodes/protocols`
- `GET /api/admin/nodes/stats`
- `GET /api/admin/nodes/available-port`
- `GET /api/admin/nodes/default-inbound`
- `GET /api/admin/nodes/group-options`
- `GET /api/admin/nodes/group-config`
- `GET /api/admin/nodes/check-port`
- `POST /api/admin/nodes`
- `PUT /api/admin/nodes/{id}`
- `DELETE /api/admin/nodes/{id}`
- `PATCH /api/admin/nodes/{id}/enable`
- `PATCH /api/admin/nodes/{id}/disable`
- `GET /api/admin/nodes/servers`
- `POST /api/admin/nodes/{id}/copy`
- `POST /api/admin/nodes/{id}/deploy`
- `POST /api/admin/nodes/{id}/deployForcibly`
- `POST /api/admin/nodes/deploy-batch`
- `POST /api/admin/nodes/switch-core`

---

## 6. 后端关键约束

### 6.1 `NodeService` 与 `NodeGroupService` 的分工

当前代码已经把节点主流程和节点组规则拆开：

- `NodeService`
- `NodeGroupService`

`NodeService` 核心职责：

- 分页查询与筛选
- 统计信息聚合
- 端口冲突校验
- 名称 + 编号唯一性校验
- 服务器存在性校验
- `coreType` 标准化
- `protocol` 是否与 `nodeType + coreType` 匹配的校验
- 创建 / 更新 / 删除 / 启停
- 获取默认 inbound 配置

`NodeGroupService` 核心职责：

- `nodeGroup` 归一化
- 节点组约束校验
- 加入已有组时对齐协议、端口、入站、出站
- 节点组候选项和配置查询
- 节点组部署范围展开
- 节点组变更后的同组节点 `deployed=0` 标记

### 6.2 协议支持矩阵

当前 `ProtocolType` 里已经编码了协议矩阵：

- 代理节点 `type=0`
  - `vless`：`xray` / `sing-box`
  - `vless-reality`：`xray` / `sing-box`
  - `hysteria2`：仅 `sing-box`
  - `shadowtls`：仅 `sing-box`
- 落地节点 `type=1`
  - `shadowsocks`：`xray` / `sing-box`
  - `socks`：`xray` / `sing-box`

这组规则非常重要，因为它决定了：

- 表单协议下拉过滤
- 创建 / 编辑校验
- 切换内核前的可行性校验

### 6.3 编辑和部署状态的联动

`updateNode` 里有一个关键设计：

如果修改了部署相关字段，节点会被标记为 `deployed = 0`。

当前判定为“实质性变更”的字段包括：

- `port`
- `type`
- `coreType`
- `serverId`
- `nodeGroup`
- `inbound`
- `rule`
- `outId`
- `level`
- `tags`
- `disabled`

这决定了 Codex 在实现新功能时，必须特别小心：

不要只改 UI 和 DTO，而忘记部署状态重置。

### 6.4 当前前端职责拆分

节点页前端目前已经不是单文件巨石模式，而是：

- `node.js`
  - DataTable 入口、列表与筛选主胶水
- `node-form-methods.js`
  - 新建 / 编辑表单、节点组联动、端口与协议处理、表单校验
- `node-deploy-methods.js`
  - 单节点部署、批量部署、切换内核、勾选态管理

后续如果继续扩展节点页，优先往对应方法模块里放，不要把所有行为重新堆回 `node.js`。

---

## 7. sing-box / xray 配置相关的真实逻辑

### 7.1 默认 inbound 生成

默认 inbound 并不是硬编码 Java Map，而是：

1. 根据 `coreType + protocol` 选择模板文件。
2. 注入动态变量。
3. 渲染成 JSON。
4. 反序列化为 `Map<String, Object>` 返回给前端。

模板来源：

- xray：`config/core/xray-inbound-*.json`
- sing-box：`config/core/sing-box-inbound-*.json`

动态值来源包括：

- UUID
- 随机密码
- server name
- Reality keypair
- short id

### 7.2 外部命令依赖

默认配置生成还依赖宿主机命令：

- xray Reality 密钥：`xray x25519`
- sing-box Reality 密钥：`sing-box generate reality-keypair`

这意味着在用 Codex 实现和测试节点管理功能时，要明确区分两件事：

1. 代码逻辑是否正确
2. 当前开发环境是否具备 `xray` / `sing-box` 可执行文件

### 7.3 切换内核不是“改个字段”

`NodeDeploymentService.switchNodeCore()` 当前做了这些事：

1. 校验节点集合不能为空。
2. 校验目标内核只允许 `xray` / `sing-box`。
3. 校验所有节点必须来自同一源内核。
4. 校验目标内核不能和源内核相同。
5. 校验协议是否被目标内核支持。
6. 校验 `outId` 指向的出站节点是否已在目标内核上。
7. 根据默认模板生成目标 inbound 骨架。
8. 将源 inbound 翻译到目标内核格式。
9. 把节点标记为未部署。
10. 联动更新引用这些节点的 `RouteRule.coreType`。
11. 如果源内核在某个服务器上已无节点使用，则禁用旧的 `ServerConfig`。
12. 如勾选重部署，则先停旧内核，再重新按服务器部署。

当前已内置的 inbound 翻译：

- `vless` / `vless-reality`
- `shadowsocks`
- `socks`

这也是为什么这个模块必须由熟悉协议的人来接。

---

## 8. 当前实现里的重点风险

如果让我用 Codex 接手，我会先把这些点列为“高优先级阅读与验证项”：

### 8.1 `disabled` 的前后端类型不统一

前端创建表单里 `newItem.disabled` 是 `boolean`，提交时转换成 `0/1`。

编辑表单里 `editedItem.disabled` 直接按 `0/1` 操作。

这虽然能跑，但在新增字段、抽公共组件或写自动化测试时很容易出错。

### 8.2 `inbound` 比较逻辑比较脆弱

`hasSubstantialChanges()` 中对 `inbound` 的比较带有字符串级比较痕迹，JSON 字段顺序、空格、格式化差异可能影响“是否需要重新部署”的判断。

如果后续要让 Codex 继续迭代节点模块，这一块值得优先重构为“结构化 JSON 比较”。

### 8.3 默认 inbound 生成依赖系统命令

如果本机没有 `xray` / `sing-box`，默认配置生成会退回兜底 key。  
这意味着测试成功不代表真实生产流程成功，必须做环境前置检查。

### 8.4 节点模块其实依赖多域对象

节点管理不仅依赖 Node 自己，还依赖：

- Server
- Tag
- RouteRule
- ServerConfig
- SSH / CoreManagement
- CoreConfigBuilder

所以不能把它当成单页面任务让 Codex 一次性盲改。

---

## 9. 如果由我来用 Codex 实现，我的操作顺序

我会分 6 个阶段推进。

### 阶段 1：建立模块地图

目标：先理解现有代码，不急着改。

我会让 Codex 做这些事：

1. 读 `NodeController`、`NodeService`、`NodeDeploymentService`
2. 读 `node.js` 和 `templates/vpn/node/*` 模板
3. 读 `Node` / `NodeRequest` / `NodeDto` / `NodeConverter`
4. 读 `ProtocolType` / `CoreType` / `NodeType`
5. 读默认 inbound 策略和模板
6. 输出一张“页面动作 -> API -> service -> repository/template”的映射表

这一阶段的产物：

- 功能边界说明
- 数据流说明
- 关键约束清单
- 风险清单

### 阶段 2：建立验收标准

目标：把“节点管理实现完成”说清楚。

我会把验收拆成：

- 页面级验收
- API 级验收
- 领域规则验收
- 配置协议验收
- 部署验收

典型验收项：

- 能创建 xray-vless 代理节点
- 能创建 sing-box-hysteria2 代理节点
- 能创建 shadowsocks 落地节点
- 不允许选择不支持的协议组合
- 主 / 备服务器端口冲突能正确提示
- 编辑实质字段后自动回到未部署
- 批量切换 xray -> sing-box 后 inbound 被正确翻译
- 切换后需要时可以联动重部署

### 阶段 3：把大任务拆成 Codex 子任务

我不会让 Codex 一次性做“完整实现节点管理”，而会拆成如下任务：

1. 页面结构和交互补齐
2. 节点 CRUD API 与 DTO 补齐
3. 端口校验与服务器联动
4. 默认 inbound 生成
5. 部署流程串联
6. 内核切换与 inbound 翻译
7. 回归测试与手工验证脚本
8. 文档与运维说明

### 阶段 4：一轮一轮驱动 Codex 改代码

每轮都固定四步：

1. 先让 Codex 读相关代码并复述理解
2. 再让 Codex 只改一个边界清晰的子任务
3. 让 Codex 跑构建 / 测试 / 静态检查
4. 让 Codex 输出改动摘要、风险和待验证项

### 阶段 5：引入协议知识增强

因为节点模块跟 sing-box / xray 配置强相关，我会额外给 Codex 提供：

- 当前仓库里已有模板目录
- 协议矩阵
- inbound 字段映射规则
- route rule / outbound 依赖说明
- xray 与 sing-box 命令行能力差异

### 阶段 6：沉淀为可复用资产

最后把这类能力固化为：

- 一份节点模块开发 SOP
- 一套高质量提示词模板
- 一个专用 SKILL
- 一组推荐 MCP

---

## 10. 适合直接喂给 Codex 的提示词

下面这些提示词可以直接用于实际开发。

### 10.1 第一轮：模块摸底

```text
你现在接手 AirOpsCat 的“节点管理”模块，请先不要写代码。

先完整阅读并分析这些入口：
1. src/main/java/com/fun90/airopscat/controller/NodeController.java
2. src/main/resources/templates/vpn/node/*
3. src/main/resources/META-INF/resources/static/js/vpn/node.js

然后继续追踪它们依赖的 service、entity、dto、converter、repository、deployment、inbound strategy、config template。

输出要求：
1. 说明页面有哪些功能动作
2. 说明每个动作对应的 API、service、实体字段
3. 说明节点模块有哪些业务约束
4. 说明 xray / sing-box / protocol / nodeType 的支持矩阵
5. 说明有哪些高风险点和待确认点

不要开始修改文件，先输出分析结果。
```

### 10.2 第二轮：只做一个子任务

```text
基于你刚才对节点管理模块的分析，现在只实现一个子任务：
[在这里填具体任务，例如：补全节点编辑时的协议筛选与端口校验]

要求：
1. 先列出会修改的文件
2. 再说明改动方案
3. 然后直接完成代码修改
4. 修改后运行最小必要验证
5. 输出改动摘要、验证结果、剩余风险

注意：
- 不要改动无关文件
- 不要破坏 xray / sing-box 协议兼容性
- 如果涉及 inbound JSON，请按结构化方式处理，不要做脆弱的字符串拼接
```

### 10.3 第三轮：协议翻译专项

```text
你现在专门负责 AirOpsCat 节点管理中的“内核切换与 inbound 翻译”。

上下文要求：
- 节点支持 xray 和 sing-box
- 需要关注 vless、vless-reality、shadowsocks、socks
- 切换时不能只修改 coreType，必须校验协议支持、outbound 依赖、route rule 联动和 redeploy 行为

请执行：
1. 阅读 NodeDeploymentService 中 switchNodeCore 相关逻辑
2. 分析现有 inbound 翻译规则是否完整
3. 找出潜在丢字段风险
4. 如有必要，补全翻译逻辑或保护性校验
5. 输出协议映射说明
```

### 10.4 第四轮：回归验证

```text
请对 AirOpsCat 的节点管理模块做一次面向回归的检查。

重点检查：
1. 节点 CRUD
2. 端口占用校验
3. 默认 inbound 生成
4. 节点复制
5. 部署与强制部署
6. 批量部署
7. 内核切换
8. route rule 联动

输出格式：
1. Findings：按严重程度列出问题
2. Missing tests：列出缺少的测试
3. Manual verification：给出手工验证步骤

如果没有发现明确 bug，也要说明残余风险。
```

---

## 11. 推荐的 Codex SKILL 设计

如果要长期用 Codex 维护这个模块，我建议专门做一个技能，比如：

- Skill 名称：`node-management`

### 11.1 这个 SKILL 要解决什么问题

让 Codex 在处理节点相关任务时，自动带上这些上下文：

- 节点管理不是单纯 CRUD
- 节点受 `nodeType + protocol + coreType` 三元关系约束
- inbound/rule 是 JSON 配置，不是普通文本
- 内核切换需要做协议翻译与部署联动
- 默认 inbound 来自模板而不是硬编码
- 测试必须区分“代码逻辑”与“系统命令依赖”

### 11.2 建议的 SKILL 目录

```text
.codex/
  skills/
    node-management/
      SKILL.md
      references/
        protocol-matrix.md
        node-module-map.md
        inbound-translation.md
        validation-checklist.md
```

### 11.3 示例 `SKILL.md`

```md
# node-management

Use this skill when working on AirOpsCat node management, node deployment, inbound generation,
core switching, or xray/sing-box protocol compatibility.

## Always load first

- src/main/java/com/fun90/airopscat/controller/NodeController.java
- src/main/java/com/fun90/airopscat/service/NodeService.java
- src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java
- src/main/resources/META-INF/resources/static/js/vpn/node.js
- references/protocol-matrix.md
- references/validation-checklist.md

## Required mental model

- Node management spans UI, API, domain validation, deployment, and protocol templates.
- Never treat inbound/rule as opaque strings when comparing semantic changes.
- coreType changes must go through switch-core flow, not normal edit flow.
- Validate compatibility across nodeType, protocol, and coreType before editing code.

## When editing

1. Map the user-facing action to controller/service/template/js first.
2. Check whether the change affects deployment state.
3. Check whether the change affects route rules or outbound node references.
4. Check whether xray/sing-box command availability matters for validation.
5. Prefer targeted tests or manual verification steps for protocol-sensitive logic.

## Output expectations

- Summarize touched files
- Call out protocol/deployment risks
- Provide manual verification steps when runtime commands are environment-dependent
```

### 11.4 建议的 references 内容

建议至少拆成 4 份参考文档：

- `protocol-matrix.md`
  - 维护 `nodeType + protocol + coreType` 支持矩阵
- `node-module-map.md`
  - 页面动作到 API / service / template / js 的映射
- `inbound-translation.md`
  - xray 与 sing-box 的 inbound 字段映射说明
- `validation-checklist.md`
  - 节点开发的验收与回归清单

---

## 12. 推荐的 MCP 组合

MCP 的价值在这个模块里主要有 4 类：

### 12.1 文件与代码检索型 MCP

用途：

- 快速全局检索 `Node`、`RouteRule`、`ServerConfig`、`coreType`
- 做跨文件调用链追踪
- 辅助重构前影响面分析

建议能力：

- 全局搜索
- 符号跳转
- 引用关系查询
- 差异对比

### 12.2 数据库查询型 MCP

用途：

- 直接验证 `node`、`route_rule`、`server_config`、`node_tag` 等表的数据状态
- 核对切换内核前后的真实数据

建议最小能力：

- 只读 SQL 查询
- 指定库连接
- 结果表格化输出

建议查询场景：

- 查某服务器有哪些节点、分别是什么 `coreType`
- 查某节点是否被 route rule 引用
- 查某次切换后旧 `server_config` 是否被禁用

### 12.3 HTTP 调试型 MCP

用途：

- 直接打节点模块 API 做集成验证
- 验证创建、编辑、切换内核的返回结构

建议能力：

- GET / POST / PUT / PATCH / DELETE
- Header / Cookie 支持
- JSON body 模板

建议覆盖：

- `/api/admin/nodes`
- `/api/admin/nodes/default-inbound`
- `/api/admin/nodes/check-port`
- `/api/admin/nodes/switch-core`

### 12.4 SSH / 远程命令型 MCP

用途：

- 验证部署后远程服务是否生成配置
- 检查 xray / sing-box 进程状态
- 查看远程配置文件内容

建议能力：

- 执行远程命令
- 查看文件
- 读取日志

适合的验证命令示例：

- `systemctl status xray`
- `systemctl status sing-box`
- `cat /usr/local/etc/xray/config.json`
- `cat /etc/sing-box/config.json`

---

## 13. 我会如何组合使用 Codex + SKILL + MCP

推荐工作流如下：

### 13.1 分析轮

- 使用 `node-management` SKILL
- 配合代码检索 MCP
- 让 Codex 先输出模块地图和风险表

### 13.2 实现轮

- 每次只做一个子任务
- 改完后立刻跑本地构建 / 测试
- 如涉及数据库状态，再用数据库 MCP 复核

### 13.3 协议轮

- 强制加载 `inbound-translation.md`
- 对涉及 xray/sing-box 的改动单独 review
- 必要时用 HTTP MCP 和 SSH MCP 做端到端验证

### 13.4 回归轮

- 先 API 回归
- 再页面手测
- 再部署验证
- 最后补文档

---

## 14. 最适合沉淀成仓库规范的内容

如果你想把“如何用 Codex 做节点管理”真正规范化，我建议在仓库里固定这几类文档：

1. 节点模块架构图
2. 协议支持矩阵
3. inbound 翻译映射表
4. 节点模块回归清单
5. Codex 提示词模板
6. `node-management` SKILL

这样以后无论是你自己、团队成员，还是另一个 AI agent 接手，都不会从零猜业务。

---

## 15. 建议的实际落地顺序

如果今天就开始推进，我建议按这个顺序做：

1. 先把本文件继续扩展为团队内 SOP
2. 再落地 `node-management` SKILL
3. 再补一份 `protocol-matrix.md`
4. 再补一份 `inbound-translation.md`
5. 然后再让 Codex 进入真正的功能开发或重构

原因很简单：

节点管理模块协议密度高、联动范围大。  
先把上下文喂饱，再让 Codex 写代码，质量会高很多。

---

## 16. 一句话结论

如果让我来用 Codex 完整实现 AirOpsCat 的“节点管理”，我不会直接写代码，而是会先把它当成一个“页面 + 领域规则 + 配置模板 + 协议翻译 + 部署 orchestration”的复合模块，先建模块地图，再按子任务迭代实现，最后把提示词、SKILL、MCP 和回归清单一起沉淀下来。
