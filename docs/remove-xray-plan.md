# 移除 Xray 与 Hysteria2 内核支持 —— 执行计划

## 背景

AirOpsCat 目前支持三种内核：Xray、Hysteria2、sing-box。  
本计划将彻底移除 Xray 与 Hysteria2 独立内核支持，**仅保留 sing-box 内核**。

注意：Hysteria2 同时也是 sing-box 支持的协议。本计划只移除 `CoreType.HYSTERIA2` 对应的独立内核，不移除 `ProtocolType.HYSTERIA2`，也不删除 sing-box 的 hysteria2 入站、出站、订阅模板和前端协议选择能力。

---

## 影响范围总览

| 类别 | 文件数 | 说明 |
|------|--------|------|
| Java 枚举 | 3 | CoreType、ProtocolType、RouteRuleType（ProtocolType 保留 hysteria2 协议，仅移除 xray 支持） |
| Java 服务类（直接删除） | 4 | XrayConfigBuilder、XrayCoreManagementStrategy、XrayDefaultInboundStrategy、Hysteria2CoreManagementStrategy |
| Java 服务类（逻辑修改） | 7 | DataInitializationConfig、NodeService、RouteRuleService、ServerConfigService、CoreDeploymentExecutor、SingBoxConfigBuilder、NodeDeploymentService |
| 资源配置文件（删除） | 5 | config/core/xray*.json × 5（保留 sing-box-inbound-hysteria2.json） |
| 订阅模板（修改） | 0 | 保留 sing-box 的 hysteria2 协议订阅输出 |
| 前端模板 | 3 | server-config/stats.html、route-rule/stats.html、server-config/modals.html（保留 node/modals.html 中的 hysteria2 协议选项） |
| 前端 JavaScript | 5 | server-config.js、route-rule.js、node-form-methods.js、node-deploy-methods.js、node.js |

---

## 执行任务列表

每个任务执行完后验证编译通过再继续下一个任务。

---

## 第一部分：移除 Xray 内核

### ✅ 任务 1：删除 Xray 专用 Java 类

直接删除以下三个文件：

- `src/main/java/com/fun90/airopscat/service/deployment/XrayConfigBuilder.java`
- `src/main/java/com/fun90/airopscat/service/core/strategy/impl/XrayCoreManagementStrategy.java`
- `src/main/java/com/fun90/airopscat/service/inbound/strategy/impl/XrayDefaultInboundStrategy.java`

---

### ✅ 任务 2：修改 `CoreType` 枚举（移除 XRAY）

**文件**：`src/main/java/com/fun90/airopscat/model/enums/CoreType.java`

移除 `XRAY("xray", "xray")` 枚举值（HYSTERIA2 在第二部分移除）。

```java
// 当前阶段结果（HYSTERIA2 暂保留，第二部分再删）
public enum CoreType {
    HYSTERIA2("hysteria2", "hysteria2"),
    SING_BOX("sing-box", "sing-box");
    // ...
}
```

---

### ✅ 任务 3：修改 `ProtocolType` 枚举（移除 xray 支持）

**文件**：`src/main/java/com/fun90/airopscat/model/enums/ProtocolType.java`

将 `VLESS`、`VLESS_REALITY`、`SHADOWSOCKS`、`SOCKS` 的 `coreTypes` 列表中的 `"xray"` 移除（`HYSTERIA2` 枚举值在第二部分处理）。

```java
VLESS("VLESS-Vision", "vless", 0, List.of("sing-box")),
VLESS_REALITY("VLESS-Vision-REALITY", "vless-reality", 0, List.of("sing-box")),
HYSTERIA2("Hysteria2", "hysteria2", 0, List.of("sing-box")),   // 保留：这是 sing-box 支持的协议，不是独立内核
SHADOWTLS("ShadowTLS", "shadowtls", 0, List.of("sing-box")),
SHADOWSOCKS("Shadowsocks", "shadowsocks", 1, List.of("sing-box")),
SOCKS("SOCKS", "socks", 1, List.of("sing-box"));
```

---

### ✅ 任务 4：修改 `RouteRuleType` 枚举（移除 xrayField）

**文件**：`src/main/java/com/fun90/airopscat/model/enums/RouteRuleType.java`

`RouteRuleType.getXrayField()` 的唯一调用方为已删除的 `XrayConfigBuilder`，可安全移除该字段和对应 getter。

```java
// 修改后（移除 xrayField 参数）
public enum RouteRuleType {
    DOMAIN("domain", "域名", "domain"),
    IP("ip", "目标 IP", "ip_cidr"),
    SOURCE_IP("source_ip", "源 IP", "source_ip_cidr"),
    PORT("port", "端口", "port"),
    NETWORK("network", "网络", "network"),
    INBOUND("inbound", "入口标签", "inbound"),
    CUSTOM("custom", "自定义", null);

    private final String value;
    private final String description;
    private final String singBoxField;
    // ...
}
```

---

### ✅ 任务 5：修改 `DataInitializationConfig`

**文件**：`src/main/java/com/fun90/airopscat/config/DataInitializationConfig.java`

`initializeNodeCoreType()` 使用 `CoreType.XRAY.getValue()` 作为旧节点回填默认值，改为 `CoreType.SING_BOX`。

```java
private void initializeNodeCoreType() {
    long updatedCount = nodeRepository.fillEmptyCoreType(CoreType.SING_BOX.getValue());
    if (updatedCount > 0) {
        log.info("Initialized coreType for {} node records with value {}", updatedCount, CoreType.SING_BOX.getValue());
    }
}
```

---

### ✅ 任务 6：修改 `NodeService`（移除 XRAY 引用）

**文件**：`src/main/java/com/fun90/airopscat/service/NodeService.java`

1. **第 697 行**：移除 `CoreType.XRAY`，只保留 `CoreType.SING_BOX`（`HYSTERIA2` 在第二部分处理）。
2. **第 724 行**：默认值 `CoreType.XRAY.getValue()` → 改为 `CoreType.SING_BOX.getValue()`。

```java
// 第 697 行（第一部分临时结果，HYSTERIA2 过滤暂保留）
.filter(type -> type == CoreType.HYSTERIA2 || type == CoreType.SING_BOX)

// 第 724 行
String normalizedCoreType = (coreType == null || coreType.trim().isEmpty())
    ? CoreType.SING_BOX.getValue() : coreType;
```

---

### ✅ 任务 7：修改 `RouteRuleService`（移除 XRAY 引用）

**文件**：`src/main/java/com/fun90/airopscat/service/RouteRuleService.java`

1. **第 94 行**：删除 `stats.put("xray", routeRuleRepository.countByCoreType(CoreType.XRAY.getValue()))`。
2. **第 112 行**：`List.of(CoreType.XRAY, CoreType.SING_BOX)` → `List.of(CoreType.HYSTERIA2, CoreType.SING_BOX)`（HYSTERIA2 在第二部分再删）。
3. **第 296 行**：移除对 xray 的条件判断，更新错误提示。

---

### ✅ 任务 8：修改 `ServerConfigService`

**文件**：`src/main/java/com/fun90/airopscat/service/ServerConfigService.java`

**第 177 行**：删除 `stats.put("xray", serverConfigRepository.countByConfigType(CoreType.XRAY.getValue()))`。

---

### ✅ 任务 9：修改 `CoreDeploymentExecutor`

**文件**：`src/main/java/com/fun90/airopscat/service/deployment/CoreDeploymentExecutor.java`

1. **第 39 行**：删除常量 `private static final String CORE_TYPE_XRAY = "xray"`。
2. **第 256-257 行**：移除 Xray 配置路径的条件分支，直接使用 sing-box 路径：

```java
// 修改后
serverConfig.setPath("/etc/sing-box/config.json");
```

---

### ✅ 任务 10：修改 `SingBoxConfigBuilder`（移除 xray fallback）

**文件**：`src/main/java/com/fun90/airopscat/service/deployment/SingBoxConfigBuilder.java`

**第 358 行**：出站节点内核类型的默认 fallback 值 `"xray"` → 改为 `"sing-box"`。

```java
// 修改后
if (!"sing-box".equalsIgnoreCase(Objects.toString(outboundNode.coreType(), "sing-box"))) {
```

---

### ✅ 任务 11：删除 Xray 资源配置文件

删除以下 5 个文件：

- `src/main/resources/config/core/xray.json`
- `src/main/resources/config/core/xray-inbound-vless.json`
- `src/main/resources/config/core/xray-inbound-vless-reality.json`
- `src/main/resources/config/core/xray-inbound-shadowsocks.json`
- `src/main/resources/config/core/xray-inbound-socks.json`

---

### ✅ 任务 12：修改前端模板（Xray 相关）

**文件 1**：`src/main/resources/templates/vpn/server-config/stats.html`（第 17-18 行）

删除 Xray 统计卡片：
```html
<!-- 删除这两行 -->
<div class="text-info fw-bold h4 mb-0">{{ stats.xray || 0 }}</div>
<div class="text-muted small">Xray</div>
```

**文件 2**：`src/main/resources/templates/vpn/route-rule/stats.html`（第 17-18 行）

同上，删除 Xray 统计展示块。

**文件 3**：`src/main/resources/templates/vpn/server-config/modals.html`（第 34、112 行）

将配置路径 placeholder 从 `/usr/local/etc/xray/config.json` 改为 `/etc/sing-box/config.json`。

---

### ✅ 任务 13：修改前端 JavaScript（Xray 相关）

**`server-config.js`**：
- 删除 `stats` 初始值中的 `xray: 0`（第 17 行）。
- 删除 `case 'XRAY': return 'bg-blue'`（第 77 行）。

**`route-rule.js`**：
- 删除 `stats` 初始值中的 `xray: 0`（第 18 行）。
- 将 `coreType: 'xray'`（第 29 行、第 204 行）改为 `coreType: 'sing-box'`。

**`node-form-methods.js`**：
- 第 233 行：默认 coreType fallback `'xray'` → `'sing-box'`。
- 第 464 行：初始化 `coreType: 'xray'` → `'sing-box'`。

**`node-deploy-methods.js`**（第 165-173 行）：

移除 `hasXray` 相关判断，直接以 sing-box 为唯一内核：
```javascript
// 删除
const hasXray = selectedNodes.some(node => node.coreType === 'xray');
if (hasXray && hasSingBox) { ... }
const sourceCoreType = hasXray ? 'xray' : 'sing-box';
this.coreSwitchTarget = sourceCoreType === 'xray' ? 'sing-box' : 'xray';
```

**`node.js`**（第 63 行）：`coreType: 'xray'` → `'sing-box'`。

---

## 第二部分：仅移除 Hysteria2 独立内核

本部分只移除 `CoreType.HYSTERIA2` 代表的独立 Hysteria2 内核运维能力。sing-box 的 `hysteria2` 协议必须继续保留，因此不要删除 `ProtocolType.HYSTERIA2`、sing-box hysteria2 配置模板、订阅模板中的 hysteria2 分支，也不要从节点协议下拉框中移除 hysteria2。

### ✅ 任务 14：删除 Hysteria2 专用 Java 类

直接删除：

- `src/main/java/com/fun90/airopscat/service/core/strategy/impl/Hysteria2CoreManagementStrategy.java`

---

### ✅ 任务 15：修改 `CoreType` 枚举（移除 HYSTERIA2）

**文件**：`src/main/java/com/fun90/airopscat/model/enums/CoreType.java`

移除 `HYSTERIA2("hysteria2", "hysteria2")`，最终仅保留：

```java
public enum CoreType {
    SING_BOX("sing-box", "sing-box");
    // ...
}
```

---

### ✅ 任务 16：确认 `ProtocolType` 枚举保留 HYSTERIA2 协议

**文件**：`src/main/java/com/fun90/airopscat/model/enums/ProtocolType.java`

不要删除 `HYSTERIA2("Hysteria2", "hysteria2", 0, List.of("sing-box"))` 枚举值。该枚举表示 sing-box 支持的 hysteria2 协议，不是独立 Hysteria2 内核。

---

### ✅ 任务 17：修改 `NodeService`（移除 HYSTERIA2 引用）

**文件**：`src/main/java/com/fun90/airopscat/service/NodeService.java`

**第 310 行**（任务 6 第一步处理后的 `HYSTERIA2` 保留处）：彻底移除 `CoreType.HYSTERIA2` 过滤条件。

```java
// 最终结果
.filter(type -> type == CoreType.SING_BOX)
```

---

### ✅ 任务 18：修改 `RouteRuleService`（移除 HYSTERIA2 引用）

**文件**：`src/main/java/com/fun90/airopscat/service/RouteRuleService.java`

**第 295 行**：移除 `CoreType.HYSTERIA2` 校验条件，仅保留 sing-box 检查。

```java
// 修改后
if (normalized == null) {
    throw new IllegalArgumentException("仅支持 sing-box 内核");
}
```

同时将第 112 行（任务 7 后的临时状态）`List.of(CoreType.HYSTERIA2, CoreType.SING_BOX)` → `List.of(CoreType.SING_BOX)`。

---

### ✅ 任务 19：修改 `NodeDeploymentService`（移除 HYSTERIA2 引用）

**文件**：`src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java`

**第 330 行**：移除 `CoreType.HYSTERIA2` 条件判断。

---

### ✅ 任务 20：确认 `SingBoxConfigBuilder` 保留 hysteria2 协议逻辑

**文件**：`src/main/java/com/fun90/airopscat/service/deployment/SingBoxConfigBuilder.java`

不要删除以下 sing-box hysteria2 协议相关逻辑：

1. `buildHysteria2Users()` 私有方法。
2. `buildHysteria2Outbound()` 私有方法。
3. `mergeUsers` 中 `protocol == "hysteria2"` 的用户合并分支。
4. 出站构建 switch 中 `case "hysteria2" -> buildHysteria2Outbound(...)` 分支。

---

### ✅ 任务 21：确认 `SingBoxDefaultInboundFactory` 保留 hysteria2 协议处理

**文件**：`src/main/java/com/fun90/airopscat/singbox/SingBoxDefaultInboundFactory.java`

不要删除以下 sing-box hysteria2 协议相关逻辑：

1. `case "hysteria2" -> "config/core/sing-box-inbound-hysteria2.json"` 模板路径分支。
2. `case "hysteria2":` 对应的模板数据构建逻辑块。

---

### ✅ 任务 22：保留 sing-box hysteria2 资源配置文件

不要删除：

- `src/main/resources/config/core/sing-box-inbound-hysteria2.json`

---

### ✅ 任务 23：保留订阅模板中的 hysteria2 协议分支

以下 7 个订阅模板文件中的 `{#if node.protocol == 'hysteria2' ...}` 条件块必须保留：

- `src/main/resources/config/subscription/clash-meta.yaml`（第 76-78 行附近）
- `src/main/resources/config/subscription/clash-mi.yaml`
- `src/main/resources/config/subscription/clash-verge.yaml`
- `src/main/resources/config/subscription/stash.yaml`
- `src/main/resources/config/subscription/shadowrocket.yaml`
- `src/main/resources/config/subscription/sing-box.json`（第 120-121 行附近）
- `src/main/resources/config/subscription/nodes/loon.html`（第 2-3 行）

同时，各文件中引用 `hysteria2` 协议的多条件判断（如 `|| (node.protocol == 'hysteria2' && node.coreType == 'sing-box')`）也必须保留。

---

### ✅ 任务 24：保留前端模板中的 hysteria2 协议联动

**文件**：`src/main/resources/templates/vpn/node/modals.html`

**第 113 行、第 370 行**：保留 `@change` 属性中的 `hysteria2`，确保选择 hysteria2 协议时仍触发协议配置联动。

```html
<!-- 保留 -->
@change="['vless', 'hysteria2'].includes(newItem.protocol) && onProtocolChange()"
```

---

### ✅ 任务 25：修改前端 JavaScript（Hysteria2 独立内核相关）

**`server-config.js`**（第 79 行）：

删除 `case 'HYSTERIA2': return 'bg-green'`。该分支对应服务器配置快照的独立内核类型，不影响节点协议列表中的 hysteria2 协议。

---

## 第三部分：数据库迁移（可选，生产环境需执行）

### 任务 26：数据库存量数据清理

若数据库中存在历史 `core_type = 'xray'` 或 `core_type = 'hysteria2'` 的记录，执行前请先备份。

```sql
-- 将 xray/hysteria2 独立内核节点迁移为 sing-box（需人工确认协议兼容性）
UPDATE node SET core_type = 'sing-box' WHERE core_type = 'xray';
UPDATE node SET core_type = 'sing-box' WHERE core_type = 'hysteria2';

-- 删除 xray/hysteria2 路由规则（格式不兼容，无法直接迁移）
DELETE FROM route_rule WHERE core_type = 'xray';
DELETE FROM route_rule WHERE core_type = 'hysteria2';

-- 删除 xray/hysteria2 服务器配置快照
DELETE FROM server_config WHERE config_type = 'xray';
DELETE FROM server_config WHERE config_type = 'hysteria2';
```

> **警告**：节点迁移后需重新部署，因为 Xray/Hysteria2 独立内核与 sing-box 的入站配置 JSON 格式不兼容，不能直接复用。此处不影响 `core_type = 'sing-box'` 且 `protocol = 'hysteria2'` 的节点。

---

## 执行顺序

```
[第一部分] Xray 移除
  任务 1  删除 Xray Java 类
  任务 2  CoreType 移除 XRAY
  任务 3  ProtocolType 移除 xray
  任务 4  RouteRuleType 移除 xrayField
  任务 5  DataInitializationConfig 改默认值
  任务 6  NodeService 移除 XRAY
  任务 7  RouteRuleService 移除 XRAY
  任务 8  ServerConfigService 移除 XRAY
  任务 9  CoreDeploymentExecutor 移除 XRAY
  任务 10 SingBoxConfigBuilder 修复 fallback
  任务 11 删除 xray 资源文件
  任务 12 前端模板 Xray 清理
  任务 13 前端 JS Xray 清理
  → 编译验证通过

[第二部分] Hysteria2 移除
  任务 14 删除 Hysteria2 Java 类
  任务 15 CoreType 移除 HYSTERIA2
  任务 16 确认 ProtocolType 保留 HYSTERIA2 协议
  任务 17 NodeService 移除 HYSTERIA2
  任务 18 RouteRuleService 移除 HYSTERIA2
  任务 19 NodeDeploymentService 移除 HYSTERIA2
  任务 20 确认 SingBoxConfigBuilder 保留 hysteria2 协议逻辑
  任务 21 确认 SingBoxDefaultInboundFactory 保留 hysteria2 协议逻辑
  任务 22 保留 sing-box hysteria2 资源文件
  任务 23 保留订阅模板 hysteria2 协议分支
  任务 24 保留前端模板 hysteria2 协议联动
  任务 25 前端 JS 清理 Hysteria2 独立内核展示
  → 编译验证通过

[第三部分] 数据库（生产执行前备份）
  任务 26 存量数据 SQL 迁移
```

---

## 注意事项

1. **`XrayDefaultInboundStrategy.generateX25519Keys()`**：该方法通过 shell 调用 `xray x25519` 生成 VLESS-Reality 密钥对。移除后需确认 `SingBoxDefaultInboundFactory` 中是否已有等效实现（调用 `sing-box generate reality-keypair`）。

2. **`ConsolePageRegistry`**：路由规则页面描述 `"为 xray、sing-box 管理路由规则"` 需更新为 `"为 sing-box 管理路由规则"`。

3. **订阅模板中的 hysteria2 多条件**：各订阅模板中除主 `{#if}` 块外，还有多处 `|| (node.protocol == 'hysteria2' ...)` 的复合条件判断（clash 类模板约有 4 处），这是 sing-box hysteria2 协议输出所需逻辑，必须保留。

4. **`node/modals.html` 中的协议 select**：`hysteria2` 是 sing-box 支持的协议选项，必须保留；只清理独立内核类型相关展示或判断。

5. **编译策略**：两个部分各自独立，建议完成第一部分并确认编译通过后再开始第二部分，便于问题定位。
