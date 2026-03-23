# AirOpsCat 节点管理模块从 0 到 1 的 Codex 实施方案

## 1. 这份文档回答什么问题

这份文档只回答一个前提明确的问题：

如果一开始 AirOpsCat 里还没有“节点管理”功能模块，我会怎么使用 Codex，把这个模块从 0 到 1 落地出来。

这里的重点不是“接手现有代码”，而是：

1. 如何定义节点管理模块
2. 如何用 Codex 驱动设计与开发
3. 如何沉淀提示词、SKILL、MCP
4. 如何把 sing-box / xray 协议能力落到产品与代码里

---

## 2. 我对“节点管理”的初始定义

在 AirOpsCat 里，“节点管理”不是一个普通后台 CRUD 页面，而是一个复合型模块：

- 它要管理节点基础信息
- 它要管理协议配置
- 它要关联服务器
- 它要关联出站 / 落地节点
- 它要参与部署
- 它要兼容 xray 与 sing-box
- 它最终要生成可运行的代理配置

所以从 0 开始时，我不会先让 Codex 写页面，而会先定义 5 个对象边界：

1. `Node`
2. `Server`
3. `RouteRule`
4. `ServerConfig`
5. `Deployment`

节点管理模块本质上是这些对象之间的协调层。

---

## 3. 从 0 到 1 的正确顺序

如果让我主导，我会按下面的顺序推进，而不是直接说“把节点管理做出来”。

### 第 1 步：先做需求建模

先把产品问题问清楚：

- 节点有哪些类型
- 每种节点支持哪些协议
- xray 和 sing-box 各支持哪些协议
- 代理节点和落地节点是什么关系
- 一个节点要不要绑定主服务器和备用服务器
- 节点的配置是否允许人工编辑 JSON
- 节点的修改是否会影响部署状态
- 节点部署是单节点、按服务器、还是按核心维度执行
- 是否支持“切换内核”
- 切换内核时是否需要自动翻译配置

这一阶段不写代码，只让 Codex 协助输出：

- 领域模型
- 用例清单
- 验收标准
- 术语表

### 第 2 步：再做协议与部署设计

节点模块和通用管理后台不一样，它在早期就必须把协议设计清楚。

我会先让 Codex 帮我确定：

- `nodeType` 设计
- `coreType` 设计
- `protocolType` 设计
- inbound 的存储形式
- route rule 的存储形式
- 默认模板机制
- 配置构建与部署边界

也就是先回答：

“节点创建之后，最后要怎么变成 xray/sing-box 可运行的配置文件？”

### 第 3 步：最后才进入页面和 API 实现

只有在前两步清楚以后，才让 Codex 去写：

- entity
- dto
- repository
- service
- controller
- qute 模板
- petite-vue 前端逻辑

---

## 4. 我会如何分阶段使用 Codex

我会把整个节点管理拆成 7 个阶段，每个阶段都用 Codex，但任务目标不同。

### 阶段 A：产品与领域分析

Codex 的任务：

- 帮我梳理业务对象
- 产出模块边界
- 产出字段清单
- 产出状态机
- 产出接口草案

这一阶段的产物：

- 节点模块设计说明
- 节点字段定义
- 协议支持矩阵
- 核心流程图

### 阶段 B：数据库与实体设计

Codex 的任务：

- 设计 `node` 表
- 设计 `node_tag` 关系
- 设计与 `server`、`route_rule` 的关联
- 定义 entity / dto / converter

这一阶段要先确定的数据字段至少包括：

- `id`
- `serverId`
- `backupServerId`
- `port`
- `protocol`
- `coreType`
- `type`
- `inbound`
- `outId`
- `rule`
- `level`
- `deployed`
- `disabled`
- `name`
- `no`
- `remark`
- `createTime`
- `updateTime`

### 阶段 C：协议与模板设计

Codex 的任务：

- 设计 xray 默认 inbound 模板
- 设计 sing-box 默认 inbound 模板
- 定义不同协议的模板变量
- 定义 Reality 密钥生成机制
- 定义默认配置生成接口

这一阶段先支持最小闭环协议：

1. `vless`
2. `vless-reality`
3. `shadowsocks`
4. `socks`

之后再扩展：

5. `hysteria2`
6. `shadowtls`

这样做的原因是：

先做主干能力，再做 sing-box 特有协议，风险更低。

### 阶段 D：领域服务实现

Codex 的任务：

- 实现 `NodeService`
- 实现节点查询、创建、编辑、删除
- 实现端口校验
- 实现协议支持校验
- 实现默认 inbound 生成
- 实现部署状态联动规则

这一阶段最重要的不是 CRUD，而是约束：

- 协议必须和 `nodeType + coreType` 匹配
- 主服务器和备用服务器不能冲突
- 端口不能冲突
- 修改关键字段后必须回到未部署

### 阶段 E：部署能力实现

Codex 的任务：

- 实现 `NodeDeploymentService`
- 定义“按服务器聚合部署”
- 把节点转为核心配置
- 调用 SSH / 核心管理能力
- 更新部署状态

这一阶段的核心目标是打通链路：

节点数据 -> 服务器配置 -> 远程下发 -> 进程重启 -> 状态回写

### 阶段 F：页面与交互实现

Codex 的任务：

- 实现节点列表页
- 实现筛选区
- 实现统计卡片
- 实现新增 / 编辑弹窗
- 实现配置查看弹窗
- 实现批量部署与批量切换内核

我会要求 Codex 在这一阶段严格遵循已有技术栈：

- Qute
- Tabler
- petite-vue
- 通用 data-table 组件模式

### 阶段 G：回归、文档、沉淀

Codex 的任务：

- 输出回归清单
- 补充测试
- 补充手工验证步骤
- 输出模块文档
- 产出 SKILL
- 产出标准提示词

---

## 5. 从 0 开始时，我如何给 Codex 下任务

关键原则只有一条：

不要一上来给 Codex 一个模糊的大任务。  
要把“节点管理”拆成边界清晰的小任务。

我会这样拆。

### 任务 1：先产出设计，不写代码

```text
你现在是 AirOpsCat 的 Java 全栈工程师，同时熟悉 xray 和 sing-box。

我们要从 0 开始设计“节点管理”模块，现在仓库里还没有这部分实现。

请先不要写代码，只做设计：

1. 定义节点管理模块的业务边界
2. 定义 Node 的核心字段
3. 定义 nodeType、coreType、protocolType
4. 给出 xray / sing-box 的协议支持矩阵
5. 说明节点和 server、route rule、deployment 的关系
6. 输出最小可落地版本的功能范围

输出格式：
1. 领域模型
2. 数据模型建议
3. API 草案
4. 页面草图说明
5. 实现优先级
```

### 任务 2：只落数据库与实体

```text
基于已经确认的节点管理设计，现在只实现数据库与后端模型层，不实现页面。

请完成：
1. Node entity
2. NodeRequest / NodeDto
3. NodeRepository
4. NodeConverter
5. 所需枚举：NodeType、CoreType、ProtocolType

要求：
1. 字段命名兼容 Quarkus + Panache
2. inbound 和 rule 使用 JSON 字段
3. 先支持 xray / sing-box
4. 协议支持矩阵写入枚举中
5. 不要提前写部署逻辑

完成后输出：
1. 修改文件
2. 字段说明
3. 后续建议
```

### 任务 3：只落领域服务

```text
现在继续实现节点管理的领域服务层，只做后端，不做前端页面。

请实现：
1. NodeService
2. 节点分页查询
3. 节点创建与编辑
4. 端口冲突校验
5. server / backupServer 校验
6. protocol 与 nodeType/coreType 组合校验
7. 部署状态联动
8. 获取默认 inbound 的 service 接口

注意：
1. 修改关键字段后要将 deployed 重置为未部署
2. 不要把 inbound 当普通字符串做脆弱拼接
3. 错误提示要清晰

完成后请运行最小验证并总结风险。
```

### 任务 4：只落默认 inbound 生成

```text
现在只实现节点模块的默认 inbound 生成能力。

目标：
1. 设计并实现 xray 默认 inbound 模板
2. 设计并实现 sing-box 默认 inbound 模板
3. 提供统一的 DefaultInboundStrategy 接口
4. 支持 vless、vless-reality、shadowsocks、socks

要求：
1. 模板文件放到 resources/config/core
2. 用 strategy 模式选择 coreType
3. reality 相关密钥生成请预留命令调用与兜底逻辑
4. 输出给前端的是结构化 JSON

不要实现页面，只把后端与模板打通。
```

### 任务 5：只落 controller API

```text
现在实现节点管理 controller API。

请实现：
1. 节点分页查询
2. 查询单个节点
3. 创建节点
4. 更新节点
5. 删除节点
6. 启用 / 禁用
7. 获取可用端口
8. 校验端口
9. 获取默认 inbound
10. 获取服务器 / 类型 / 协议选项

要求：
1. 响应结构适合 data table 页面使用
2. 错误响应统一
3. 先不要做内核切换和部署
```

### 任务 6：只落页面

```text
现在为 AirOpsCat 实现节点管理页面。

技术要求：
1. Qute 模板
2. Tabler UI
3. petite-vue
4. 复用 common/data-table.js 风格

页面能力：
1. 节点列表
2. 搜索和筛选
3. 统计卡片
4. 新增弹窗
5. 编辑弹窗
6. 删除确认弹窗
7. 查看配置弹窗

注意：
1. 协议选项要随着 nodeType/coreType 联动
2. 端口要支持校验与自动获取
3. inbound / rule 允许 JSON 编辑
4. 保持与现有后台风格一致
```

### 任务 7：只落部署与切换内核

```text
现在实现节点管理剩余的高级能力：

1. 单节点部署
2. 批量部署
3. 节点复制
4. 切换内核
5. inbound 翻译
6. route rule 联动
7. 可选重部署

要求：
1. 切换内核前必须校验协议是否兼容目标内核
2. 若存在 outId 依赖，必须校验出站节点是否已在目标内核
3. 切换后要将节点置为未部署
4. 如开启 redeploy，先停旧核心再重新部署
5. 支持 xray 和 sing-box 的 inbound 字段映射

完成后输出协议映射与验证步骤。
```

---

## 6. 我会怎么定义 MVP

从 0 到 1 时，不能一开始就把所有协议和所有部署动作都做全。

我会先切出一个最小可交付版本。

### MVP 必须包含

- 节点 CRUD
- 服务器选择
- 主 / 备服务器端口校验
- 节点类型
- 核心类型
- 协议选择
- 默认 inbound 生成
- 查看配置
- 单节点部署

### MVP 可以晚一点做

- 批量部署
- 节点复制
- 标签联动
- 内核切换
- route rule 联动
- hysteria2
- shadowtls

原因：

如果一开始就把内核切换和完整协议翻译纳入首版，Codex 很容易在复杂联动里出错。  
先把“节点创建 -> 配置生成 -> 下发部署”闭环跑通，收益最大。

---

## 7. 我会如何让 Codex 避免跑偏

从 0 开始做这种模块，Codex 最容易犯 5 类错误。

### 错误 1：把节点管理写成普通表单 CRUD

规避方式：

- 在提示词里反复强调这是“协议 + 部署”模块
- 要求先做领域设计，不允许直接开始写页面

### 错误 2：协议支持矩阵散落在前后端

规避方式：

- 先把协议矩阵收敛到枚举或统一配置
- 前端只消费后端提供的协议选项

### 错误 3：把 inbound 当字符串处理

规避方式：

- 明确要求 `Map<String, Object>` 或结构化 JSON
- 只有入库时才序列化

### 错误 4：部署状态规则遗漏

规避方式：

- 把“哪些字段变更后要重置 deployed”写进验收标准
- 在 service 层统一处理

### 错误 5：把 sing-box / xray 差异隐藏掉

规避方式：

- 单独设计 `coreType`
- 单独设计默认模板 strategy
- 单独设计内核切换翻译逻辑

---

## 8. 从 0 开始时建议沉淀的 SKILL

如果这是一个长期项目，我会在功能落地的同时就创建专用技能：

- 技能名：`node-management-bootstrap`

这个技能不是给“接手现状”用的，而是给“从 0 开始设计与实现节点模块”用的。

### 建议的 `SKILL.md`

```md
# node-management-bootstrap

Use this skill when designing or implementing the node management module from scratch in AirOpsCat.

## Mission

Build node management as a protocol-aware and deployment-aware module, not just CRUD.

## Required design order

1. Define business scope
2. Define entity/data model
3. Define protocol matrix
4. Define default inbound strategy
5. Define service rules
6. Define controller API
7. Define UI
8. Define deployment and core switching

## Hard rules

- Treat inbound and rule as structured JSON
- Centralize protocol support matrix
- Keep coreType explicit
- Reset deployed status after substantial changes
- Validate compatibility across nodeType, protocol, and coreType
- Do not mix edit-flow core changes with switch-core flow

## Preferred deliverables

- design doc
- enums
- entity/dto/repository
- service
- controller
- templates/js
- regression checklist
```

### 建议的 references

- `domain-model.md`
- `protocol-matrix.md`
- `inbound-template-plan.md`
- `deployment-flow.md`
- `acceptance-checklist.md`

---

## 9. 从 0 开始时推荐的 MCP

### 9.1 文档 / 代码型 MCP

用途：

- 看已有模块的实现风格
- 参考 server、route-rule、account 等模块的写法
- 快速建立项目内一致性

### 9.2 数据库型 MCP

用途：

- 验证 node 表设计
- 验证与 server / route_rule 的关系
- 检查部署状态回写结果

### 9.3 HTTP 型 MCP

用途：

- 在页面没完全做完前先验证 API
- 用接口回归节点创建、修改、默认 inbound 获取

### 9.4 SSH / 远程执行型 MCP

用途：

- 验证部署逻辑
- 验证配置文件生成
- 验证 xray / sing-box 进程状态

### 9.5 命令执行型 MCP

用途：

- 验证本机是否存在 `xray` / `sing-box`
- 生成 reality keypair
- 构建和运行 Quarkus

---

## 10. 我实际会怎么推进第一周工作

如果这是一个真实项目，我会按下面节奏使用 Codex。

### Day 1

- 让 Codex 输出节点模块设计文档
- 明确字段、流程、协议矩阵
- 评审并冻结 MVP 范围

### Day 2

- 让 Codex 落 entity / dto / enum / repository
- 手工 review 数据模型

### Day 3

- 让 Codex 落 `NodeService`
- 做协议校验与端口校验
- 输出 API 草案

### Day 4

- 让 Codex 落默认 inbound 模板与 strategy
- 本地验证 xray / sing-box 配置结构

### Day 5

- 让 Codex 落 controller 与基础页面
- 打通 CRUD + 默认 inbound + 查看配置

### Day 6

- 让 Codex 落部署能力
- 进行端到端验证

### Day 7

- 让 Codex 落批量部署 / 复制 / 切换内核
- 补文档、回归清单、SKILL

---

## 11. 我最看重的验收标准

从 0 实现时，我会要求 Codex 最终至少满足这些验收点：

1. 能创建一个 xray `vless` 代理节点
2. 能创建一个 sing-box `vless-reality` 代理节点
3. 能创建一个 `shadowsocks` 落地节点
4. 页面只显示当前可选协议，不出现非法组合
5. 主服务器和备用服务器的端口冲突能正确拦截
6. 节点编辑关键字段后自动变成未部署
7. 能查看节点 inbound / rule 配置
8. 能完成至少一种核心的真实部署
9. 批量部署不会破坏单节点部署逻辑
10. 切换内核时能完成配置翻译和联动校验

---

## 12. 一句话方法论

从 0 开始时，我不会把 Codex 当代码生成器，而会把它当“设计助理 + 实现工程师 + 回归助手”。

正确用法是：

先让 Codex 帮我把节点管理设计清楚，  
再让它按领域模型、协议模板、服务规则、页面交互、部署链路分阶段实现，  
最后把经验沉淀成提示词、SKILL 和 MCP 工作流。

这才是用 Codex 落地这种高复杂度模块的正确方式。
