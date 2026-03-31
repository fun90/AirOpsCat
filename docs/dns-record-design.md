# DNS记录功能设计

## 1. 目标

在 AirOpsCat 中增加 DNS 记录管理能力，首期只接入 Cloudflare API，但整体架构要支持未来切换不同 DNS 服务商。

本次设计调整后，核心原则变为：

1. `DNS服务商` 是独立管理对象，保存服务商 API 信息
2. 域名不再拥有独立的 `DomainDnsConfig`
3. 一个 DNS 服务商配置可以被多个域名复用
4. 域名可以随时切换绑定不同 DNS 服务商
5. DNS 记录仍然以域名为中心管理

当前已确认的业务约束：

1. 代码风格保持与现有项目一致
2. 尽量复用现有 `DomainController`、`DomainService`、`DataTable` 等模式
3. 功能归属“设备”模块
4. 首期“扫描DNS”不实现 Cloudflare 异步扫描接口，而是定义为“将云端记录拉取到本地”
5. 本地修改 DNS 后不立即调用云端 API
6. 通过显式 `pull` / `push` 实现云端与本地同步
7. `DNS服务商` 作为独立菜单管理
8. 域名侧需要支持切换当前绑定的 DNS 服务商

## 2. 页面与入口设计

## 2.1 设备模块菜单

设备模块左侧菜单调整为：

1. `设备 -> 域名`
2. `设备 -> DNS服务商`
3. `设备 -> 服务器`
4. `设备 -> 服务器监控`
5. `设备 -> 一键装机`

新增独立菜单项 `DNS服务商`。

## 2.2 DNS服务商页面

`DNS服务商` 为独立页面，直接出现在左侧菜单。

页面路由建议：

- `/console/device/dns-provider`

建议在 `ConsolePageRegistry` 中新增页面：

- `uri = /device/dns-provider`
- `showInMenu = true`

建议模板与脚本：

- `templates/device/dns-provider/`
- `static/js/device/dns-provider.js`

页面职责：

1. 管理 DNS 服务商配置
2. 测试服务商连接
3. 查看服务商被哪些域名使用
4. 支持启用 / 停用

## 2.3 域名页面职责

`设备 -> 域名` 页面继续负责域名资产管理，同时增加 DNS 服务商绑定与 DNS 记录入口。

域名列表新增字段：

1. `当前DNS服务商`
2. `同步状态`
3. `最近同步时间`

域名列表新增操作：

1. `切换DNS服务商`
2. `查看DNS记录`
3. `拉取DNS记录`
4. `推送DNS记录`

操作语义：

- `切换DNS服务商`
  - 为当前域名绑定或更换 `DnsProviderConfig`
- `查看DNS记录`
  - 在当前域名页内查看该域名本地 DNS 记录
- `拉取DNS记录`
  - 基于当前绑定的服务商调用云端列表接口，将记录写入本地
- `推送DNS记录`
  - 将本地未推送变更批量推送到当前绑定服务商

域名记录操作前置条件：

1. 域名必须先绑定 DNS 服务商
2. 服务商配置必须处于 `ENABLED`
3. 服务商配置必须通过结构校验

## 2.4 DNS记录展示区域

首期不新增单独菜单页，DNS 记录区复用当前域名页。

建议进入方式：

- `/console/device/domain?domainId={id}`

页面进入后自动聚焦当前域名的 DNS 记录区域。

DNS 记录区首期支持：

1. 搜索
2. `type` 筛选
3. 排序字段：
   - `type`
   - `name`
   - `content`
4. 分页
5. 单条新增
6. 单条编辑
7. 单条删除
8. 批量新增
9. 批量修改
10. 批量删除

首期记录类型优先支持：

- `A`
- `AAAA`
- `CNAME`
- `TXT`
- `MX`
- `SRV`
- `NS`

复杂记录类型和服务商特有字段，先通过扩展 JSON 兼容。

## 3. Cloudflare API 结论

本期只依赖以下能力：

### 3.1 拉取记录

- `GET /zones/{zone_id}/dns_records`

用于：

1. 将云端记录拉取到本地
2. 拉取后覆盖或更新本地快照
3. push 成功后再次 pull，确保本地与云端一致

支持能力：

1. 分页
2. 结构化筛选
3. 排序

### 3.2 批量推送

- `POST /zones/{zone_id}/dns_records/batch`

支持：

- `deletes`
- `patches`
- `puts`
- `posts`

已确认的首期策略：

1. 批量修改默认按 `patch`
2. 不默认使用 `put`

### 3.3 鉴权

Cloudflare 鉴权统一使用：

- `Authorization: Bearer <API_TOKEN>`

## 4. 数据模型设计

## 4.1 设计原则

这一版数据模型遵循三个原则：

1. 服务商配置与域名关系解耦
2. 实体字段只保留标准业务语义
3. 服务商特有字段全部进入 JSON 扩展字段

这样可以避免把 Cloudflare 专用字段固化到表结构里，也能支撑多个域名复用同一服务商配置。

## 4.2 实体一：`DnsProviderConfig`

说明：

- 原设计里的 `DomainDnsProviderConfig` 命名不再使用
- 原设计里的 `DomainDnsConfig` 也不再表示“某个域名当前使用的 DNS 配置”
- 统一重命名为 `DnsProviderConfig`

用途：

- 表示平台中的一个 DNS 服务商配置
- 保存服务商 API 信息与连接参数
- 可被多个域名复用

建议字段：

- `id`
- `providerType`
- `status`
- `displayName`
- `credentialJson`
- `extensionJson`
- `lastCheckTime`
- `lastCheckStatus`
- `lastCheckMessage`
- `remark`
- `createTime`
- `updateTime`

字段说明：

- `providerType`
  - 服务商类型，例如 `CLOUDFLARE`
- `status`
  - 配置状态，例如 `ENABLED`、`DISABLED`
- `displayName`
  - 页面展示名称，例如“Cloudflare 主账号”
- `credentialJson`
  - 敏感凭证 JSON，例如 `apiToken`
- `extensionJson`
  - 非敏感扩展 JSON，例如默认 `accountId`
- `lastCheckStatus`
  - 最近一次连接测试结果

已确认：

- `credentialJson` 整体加密存储

建议：

- `credentialJson` 使用 `@Convert(converter = CryptoConverter.class)` 加密
- `extensionJson` 采用 JSON 列定义保存

Cloudflare 首期建议数据结构：

- `credentialJson`
  - `apiToken`

- `extensionJson`
  - `accountId`

说明：

- `zoneId` 不建议存到服务商配置层，因为 Cloudflare 的 zone 与域名强相关
- 与域名绑定相关的参数应下沉到域名绑定层

## 4.3 域名与服务商绑定设计

域名需要记录当前绑定的 DNS 服务商。

建议两种实现方式中优先选择第一种：

### 方案 A：在 `Domain` 上增加绑定字段

建议字段：

- `dnsProviderConfigId`
- `dnsProviderType`
- `dnsSyncStatus`
- `dnsLastSyncTime`

适用原因：

1. 当前一个域名只需要绑定一个服务商
2. 切换服务商是覆盖当前绑定关系
3. 可以减少额外绑定表复杂度

### 方案 B：新增 `DomainDnsBinding`

适用于未来一个域名可能存在更复杂绑定关系的情况。

如果实现时希望保持 `Domain` 纯净，也可以新增：

- `id`
- `domainId`
- `dnsProviderConfigId`
- `providerType`
- `providerDomainKey`
- `syncStatus`
- `lastSyncTime`
- `extensionJson`
- `remark`
- `createTime`
- `updateTime`

首期建议按方案 A 设计，除非实现阶段发现当前 `Domain` 结构不适合承载。

说明：

- `providerDomainKey` 表示服务商侧该域名的唯一定位参数
- 对 Cloudflare 首期来说可在域名侧扩展字段中保存 `zoneId`

## 4.4 实体二：`DomainDnsRecord`

用途：

- 保存某个域名的本地 DNS 记录快照
- 支撑本地编辑、排序、筛选、未推送状态和 push 操作

建议字段：

- `id`
- `domainId`
- `dnsProviderConfigId`
- `externalRecordId`
- `name`
- `fullName`
- `type`
- `content`
- `ttl`
- `proxied`
- `priority`
- `status`
- `remark`
- `bizTagsJson`
- `extensionJson`
- `rawData`
- `lastSyncTime`
- `createTime`
- `updateTime`

字段说明：

- `dnsProviderConfigId`
  - 记录当前快照来自哪个服务商配置，便于域名切换服务商后做隔离判断
- `externalRecordId`
  - 云端记录 ID，属于跨厂商可复用概念
- `status`
  - 本地记录状态
- `extensionJson`
  - 服务商扩展字段，例如 Cloudflare 的 `comment`、`tags`、其他特殊设置
- `rawData`
  - 云端原始响应 JSON，用于保底兼容

## 4.5 本期不需要的实体

本期不再设计 `DomainDnsScanRecord`，因为已确认：

- “扫描DNS”不做 Cloudflare 异步扫描
- 本期只做 pull 云端记录到本地

## 4.6 枚举建议

- `DnsProviderType`
  - `CLOUDFLARE`

- `DnsProviderConfigStatus`
  - `ENABLED`
  - `DISABLED`

- `DnsProviderCheckStatus`
  - `SUCCESS`
  - `FAILED`
  - `NOT_CHECKED`

- `DnsSyncStatus`
  - `NOT_SYNCED`
  - `SYNCED`
  - `PULLING`
  - `PUSHING`
  - `SYNC_FAILED`

- `DnsRecordStatus`
  - `SYNCED`
  - `PENDING_CREATE`
  - `PENDING_UPDATE`
  - `PENDING_DELETE`
  - `SYNC_FAILED`

## 5. 状态流设计

## 5.1 域名级同步状态

`Domain.dnsSyncStatus` 或 `DomainDnsBinding.syncStatus` 表示该域名当前 DNS 记录的云端同步状态。

建议语义：

- `SYNCED`
  - 本地记录与云端一致
- `NOT_SYNCED`
  - 本地有变更但尚未 push
- `PULLING`
  - 正在从云端拉取
- `PUSHING`
  - 正在推送到云端
- `SYNC_FAILED`
  - 最近一次 pull 或 push 失败

说明：

- 同步状态不再挂在 `DnsProviderConfig`
- 因为一个服务商可以被多个域名复用，状态必须归属于域名

## 5.2 本地编辑后的状态变化

你已确认：

- 本地修改后不立即调用 API
- 本地修改后未 push，域名级同步状态应为“未同步”

因此建议规则：

1. 本地新增记录：
   - `DomainDnsRecord.status = PENDING_CREATE`
   - `Domain.dnsSyncStatus = NOT_SYNCED`

2. 本地修改记录：
   - `DomainDnsRecord.status = PENDING_UPDATE`
   - `Domain.dnsSyncStatus = NOT_SYNCED`

3. 本地删除记录：
   - `DomainDnsRecord.status = PENDING_DELETE`
   - `Domain.dnsSyncStatus = NOT_SYNCED`

说明：

- 删除时先逻辑标记 `PENDING_DELETE`
- push 成功后再真正清理或用 pull 结果覆盖

## 5.3 切换服务商后的状态变化

当域名切换绑定 DNS 服务商时，建议执行：

1. 清空或归档旧服务商产生的本地记录快照
2. 将域名级 `dnsSyncStatus` 置为 `NOT_SYNCED`
3. 清空 `dnsLastSyncTime`
4. 引导用户先执行一次 `pull`

说明：

- 切换服务商后，不应继续沿用旧服务商的 `externalRecordId`
- 首期建议切换后直接以新服务商为准重建本地快照

## 6. Provider 抽象层设计

## 6.1 目标

把平台业务模型和 Cloudflare 具体实现解耦。

建议层次：

1. 业务服务层
2. `DnsProviderClientRegistry`
3. `DnsProviderClient`
4. `CloudflareDnsRestClient`

## 6.2 统一接口

建议统一接口：

```java
public interface DnsProviderClient {

    DnsProviderType getProviderType();

    void validateConfigStructure(DnsProviderConfig config);

    DnsProviderValidationResult testConnection(DnsProviderConfig config);

    DnsRecordPageResponse listRecords(DnsProviderRuntimeContext context, DnsRecordQuery query);

    DnsBatchChangeResponse batchChangeRecords(DnsProviderRuntimeContext context, DnsBatchChangeRequest request);
}
```

统一配置对象建议：

```java
public class DnsProviderConfig {
    private DnsProviderType providerType;
    private Map<String, Object> credentials;
    private Map<String, Object> extensions;
}
```

新增运行时上下文建议：

```java
public class DnsProviderRuntimeContext {
    private DnsProviderConfig providerConfig;
    private Long domainId;
    private String domainName;
    private Map<String, Object> domainExtensions;
}
```

说明：

- `credentials` 对应 `credentialJson`
- `extensions` 对应服务商级 `extensionJson`
- `domainExtensions` 对应域名绑定侧扩展字段

接口职责说明：

- `validateConfigStructure`
  - 只做本地结构校验
  - 例如检查 `apiToken` 是否存在，字段格式是否合理

- `testConnection`
  - 调用服务商 API 做远程连通性校验
  - 例如校验 token 是否有效、权限是否足够

- `listRecords`
  - 使用服务商配置 + 域名上下文读取指定域名记录

- `batchChangeRecords`
  - 使用服务商配置 + 域名上下文推送指定域名记录

## 6.3 Registry

建议新增：

```java
@ApplicationScoped
public class DnsProviderClientRegistry {
    public DnsProviderClient getClient(DnsProviderType providerType) { ... }
}
```

## 6.4 Cloudflare 实现

建议拆成两层：

1. `CloudflareDnsRestClient`
2. `CloudflareDnsProviderClient`

职责：

### `CloudflareDnsRestClient`

负责 HTTP 接口：

- `GET /zones/{zoneId}/dns_records`
- `POST /zones/{zoneId}/dns_records/batch`

### `CloudflareDnsProviderClient`

负责：

1. 从 `credentialJson` 读取 `apiToken`
2. 从域名绑定扩展字段读取 `zoneId`
3. 实现 `validateConfigStructure`
4. 实现 `testConnection`
5. 组装 Bearer Token
6. 将 Cloudflare JSON 响应转换为平台统一 DTO
7. 将平台批量请求转换为 Cloudflare `/batch` 请求体

Cloudflare 侧首期实现建议：

1. 不额外定义完整的 Cloudflare DTO
2. 在适配层使用 `JsonNode`、`Map<String, Object>` 或等价轻量结构解析响应
3. 仅保留平台统一 DTO

这样做的原因：

1. 首期减少样板代码
2. Cloudflare 特有字段本来就主要落到 `extensionJson` 和 `rawData`
3. 适合当前探索阶段快速落地

注意：

- 即使省略 Cloudflare DTO，也要保留 `CloudflareDnsProviderClient` 这一层
- 不建议让业务层直接处理 Cloudflare 原始 JSON

## 7. 后端模块设计

## 7.1 Controller 设计

建议新增：

1. `DnsProviderConfigController`
2. `DomainDnsRecordController`
3. `DomainDnsBindingController` 或在 `DomainController` 中扩展绑定接口

### DNS服务商控制器

路径建议：

- `/api/admin/dns-provider-configs`

建议接口：

1. `GET /api/admin/dns-provider-configs`
2. `GET /api/admin/dns-provider-configs/{id}`
3. `POST /api/admin/dns-provider-configs`
4. `PUT /api/admin/dns-provider-configs/{id}`
5. `DELETE /api/admin/dns-provider-configs/{id}`
6. `POST /api/admin/dns-provider-configs/{id}/test`
7. `GET /api/admin/dns-provider-configs/{id}/domains`

### 域名绑定控制器

如果放在 `DomainController` 中，建议接口：

1. `PUT /api/admin/domains/{domainId}/dns-provider`
2. `DELETE /api/admin/domains/{domainId}/dns-provider`
3. `GET /api/admin/domains/{domainId}/dns-provider`

语义：

- 绑定或切换当前域名使用的 DNS 服务商
- 删除绑定表示当前域名暂不接入 DNS 管理

### 记录控制器

路径建议：

- `/api/admin/domains/{domainId}/dns-records`

建议接口：

1. `GET /api/admin/domains/{domainId}/dns-records`
2. `POST /api/admin/domains/{domainId}/dns-records/pull`
3. `POST /api/admin/domains/{domainId}/dns-records/push`
4. `POST /api/admin/domains/{domainId}/dns-records`
5. `PUT /api/admin/domains/{domainId}/dns-records/{recordId}`
6. `DELETE /api/admin/domains/{domainId}/dns-records/{recordId}`
7. `POST /api/admin/domains/{domainId}/dns-records/batch`

接口语义：

- `pull`
  - 将当前绑定服务商上的云端记录拉取到本地
- `push`
  - 将本地未推送记录推送到当前绑定服务商
- `batch`
  - 只修改本地记录和本地状态，不直接调用云端 API

## 7.2 Service 设计

建议新增：

1. `DnsProviderConfigService`
2. `DomainDnsBindingService` 或 `DomainService` 扩展绑定逻辑
3. `DomainDnsRecordService`
4. `DomainDnsPullService`
5. `DomainDnsPushService`

### `DnsProviderConfigService`

负责：

1. DNS 服务商配置增删改查
2. 调用 `validateConfigStructure` 做结构校验
3. 调用 `testConnection` 做连接测试
4. 脱敏返回
5. 查询配置被哪些域名使用

### `DomainDnsBindingService`

负责：

1. 域名绑定服务商
2. 域名切换服务商
3. 切换后清理或重置本地 DNS 状态
4. 回填域名 DNS 概览字段

### `DomainDnsRecordService`

负责：

1. 本地记录查询
2. 单条新增 / 修改 / 删除
3. 批量新增 / 修改 / 删除
4. 本地记录状态维护
5. 更新域名级 `syncStatus`

### `DomainDnsPullService`

负责：

1. 根据域名解析出当前绑定服务商
2. 调用 provider `listRecords`
3. 将云端记录写入本地
4. 清理本地已失效记录
5. 更新 `lastSyncTime`
6. 更新域名级 `syncStatus`

### `DomainDnsPushService`

负责：

1. 读取本地 `PENDING_*` 记录
2. 构建统一批量请求
3. 调用 provider `batchChangeRecords`
4. push 成功后再执行一次 pull
5. 将域名状态改为 `SYNCED`
6. 失败时改为 `SYNC_FAILED`

## 7.3 Repository 设计

建议新增：

1. `DnsProviderConfigRepository`
2. `DomainDnsRecordRepository`

如果采用绑定表，再新增：

3. `DomainDnsBindingRepository`

建议查询能力：

- 按 `providerType`
- 按 `status`
- 按 `domainId`
- 按 `dnsProviderConfigId`
- 按 `externalRecordId`
- 按 `type`
- 按 `status`

## 8. DTO 设计

## 8.1 页面层 DTO

建议新增：

- `DnsProviderConfigDto`
- `DnsProviderConfigRequest`
- `DomainDnsProviderBindingDto`
- `DomainDnsProviderBindingRequest`
- `DomainDnsRecordDto`
- `DomainDnsRecordRequest`
- `DomainDnsRecordBatchRequest`
- `DomainDnsRecordBatchItemRequest`
- `DomainDnsPullResponse`
- `DomainDnsPushResponse`

## 8.2 Provider 统一 DTO

建议新增：

- `DnsProviderConfig`
- `DnsProviderRuntimeContext`
- `DnsProviderValidationResult`
- `DnsRecordQuery`
- `DnsProviderRecord`
- `DnsRecordPageResponse`
- `DnsBatchChangeRequest`
- `DnsBatchChangeItem`
- `DnsBatchChangeResponse`

## 8.3 Cloudflare 轻量适配方案

首期不强制新增独立的 Cloudflare DTO。

建议方案：

1. 平台层保留强类型统一 DTO
2. Cloudflare 适配层通过 `JsonNode`、`Map<String, Object>` 解析和组装 JSON
3. Cloudflare 特有字段进入：
   - `extensionJson`
   - `rawData`

如果后续出现以下情况，再补 Cloudflare DTO：

1. 复杂记录类型支持增多
2. 错误处理需要更强类型约束
3. 多服务商实现进入稳定维护期

## 9. 核心流程设计

## 9.1 配置 DNS 服务商

流程：

1. 用户进入 `设备 -> DNS服务商`
2. 新增或编辑 `DnsProviderConfig`
3. 填写服务商类型、名称、认证信息
4. 保存后写入：
   - 标准字段 -> 主列
   - `apiToken` -> `credentialJson`
   - `accountId` -> `extensionJson`
5. 用户可额外点击“测试连接”

## 9.2 域名绑定或切换服务商

流程：

1. 用户在域名列表点击“切换DNS服务商”
2. 选择一个已启用的 `DnsProviderConfig`
3. 根据服务商填写当前域名所需扩展参数
4. 保存绑定关系
5. 如果是切换服务商：
   - 清理旧本地记录快照
   - 重置同步状态
   - 提示用户重新 pull

Cloudflare 首期建议域名绑定扩展字段：

- `zoneId`

## 9.3 Pull 云端记录到本地

流程：

1. 用户点击“拉取DNS记录”
2. 后端根据 `domainId` 查询当前绑定的 `DnsProviderConfig`
3. 组装 `DnsProviderRuntimeContext`
4. 调用 `DnsProviderClientRegistry`
5. 使用 `CloudflareDnsProviderClient.listRecords`
6. 拉取云端记录
7. 将结果 upsert 到本地 `DomainDnsRecord`
8. 删除本地不存在于云端的旧记录
9. 更新：
   - `lastSyncTime`
   - `syncStatus = SYNCED`

接口：

- `POST /api/admin/domains/{domainId}/dns-records/pull`

## 9.4 本地编辑记录

流程：

1. 用户在本地记录区新增 / 编辑 / 删除记录
2. 后端只修改本地表
3. 按规则标记 `PENDING_*`
4. 将域名级 `syncStatus = NOT_SYNCED`

说明：

- 不立即打 Cloudflare API
- 本地数据库是 push 前的工作区

## 9.5 Push 本地记录到云端

流程：

1. 用户点击“推送DNS记录”
2. 后端读取该域名下所有 `PENDING_*` 记录
3. 构建统一 `DnsBatchChangeRequest`
4. Cloudflare 适配器映射到：
   - `posts`
   - `patches`
   - `deletes`
5. 调用 Cloudflare `/batch`
6. 成功后再执行一次 `pull`
7. 刷新本地快照
8. 更新：
   - `syncStatus = SYNCED`
   - `lastSyncTime`

接口：

- `POST /api/admin/domains/{domainId}/dns-records/push`

## 10. 域名页增强设计

## 10.1 `DomainDto` 扩展字段

建议新增：

- `dnsProviderConfigId`
- `dnsProviderName`
- `dnsProviderType`
- `dnsSyncStatus`
- `dnsLastSyncTime`

如果绑定侧有额外域名参数，也可增加：

- `dnsProviderDomainKey`

## 10.2 `DomainService` 查询增强

查询域名分页时：

1. 继续读取 `Domain`
2. 批量读取对应的 `DnsProviderConfig`
3. 回填当前 DNS 服务商、同步状态、最近同步时间

说明：

- 如果采用方案 A，可直接从 `Domain` 取绑定字段再 join 配置
- 如果采用方案 B，则通过绑定表批量回填

## 11. 前端实现建议

## 11.1 域名页

继续基于：

- `templates/device/domain/*`
- `static/js/device/domain.js`

建议新增：

1. 表格三列：
   - 当前DNS服务商
   - 同步状态
   - 最近同步时间
2. 行操作：
   - 切换DNS服务商
   - 查看DNS记录
   - 拉取DNS记录
   - 推送DNS记录
3. 页内 DNS 记录区域

## 11.2 DNS服务商页

新增：

- `templates/device/dns-provider/content.html`
- `templates/device/dns-provider/table.html`
- `templates/device/dns-provider/modals.html`
- `static/js/device/dns-provider.js`

页面定位：

- 全局服务商配置管理页
- 展示服务商基本信息、状态、最近检测时间、关联域名数

## 11.3 DNS记录区交互

建议：

1. 记录列表支持 `type` 筛选
2. 记录列表支持按 `type`、`name`、`content` 排序
3. 支持勾选
4. 支持批量新增 / 批量修改 / 批量删除
5. 批量操作只影响本地状态

## 12. 安全与存储设计

## 12.1 凭证存储

已确认：

- `credentialJson` 整体加密存储

建议复用现有：

- `CryptoConverter`

## 12.2 脱敏返回

接口返回服务商配置时：

1. 不返回完整敏感凭证
2. 只返回是否已配置
3. 必要时返回掩码值

## 12.3 JSON 字段使用原则

建议约束：

1. 通用业务字段必须独立成列
2. 敏感信息进入 `credentialJson`
3. 服务商级扩展信息进入 `extensionJson`
4. 域名绑定侧扩展信息进入绑定扩展字段或 `Domain` 扩展列
5. 原始响应进入 `rawData`

## 13. Native Image 与反射要求

根据仓库要求，以下新增类型若作为控制器请求/响应或 JSON 序列化类型，需要加入：

- `JsonReflectionConfiguration`

实现时大概率要补：

- `DnsProviderConfigDto`
- `DnsProviderConfigRequest`
- `DomainDnsProviderBindingDto`
- `DomainDnsProviderBindingRequest`
- `DomainDnsRecordDto`
- `DomainDnsRecordRequest`
- `DomainDnsRecordBatchRequest`
- `DomainDnsPullResponse`
- `DomainDnsPushResponse`
- 统一 Provider DTO
- 新增实体

## 14. 分阶段实施建议

## 阶段一：DNS服务商管理

1. 新增 `DnsProviderConfig`
2. 新增独立菜单页 `/console/device/dns-provider`
3. 完成服务商配置增删改查
4. 完成服务商连接测试

## 阶段二：域名绑定与 pull

1. 域名列表新增 当前DNS服务商 / 同步状态 / 最近同步时间
2. 增加“切换DNS服务商”
3. 完成域名与服务商绑定
4. 完成云端记录 pull 到本地

## 阶段三：本地编辑与 push

1. 本地记录新增 / 修改 / 删除
2. 批量新增 / 批量修改 / 批量删除
3. 记录状态维护
4. 域名级 `syncStatus` 维护
5. `push` 到 Cloudflare
6. push 后自动 pull 刷新本地

## 15. 最终方案总结

最终方案已经收敛为：

1. `DNS服务商` 为独立菜单与独立管理对象
2. 原 `DomainDnsConfig` 重命名为 `DnsProviderConfig`
3. `DnsProviderConfig` 表示 DNS 服务商配置，包含 API 信息
4. 一个服务商配置可以被多个域名复用
5. 域名可以切换当前绑定的 DNS 服务商
6. 域名记录仍然按域名维度 pull / push
7. `syncStatus` 属于域名，不属于服务商配置
8. 服务商特有字段进入 `credentialJson` / `extensionJson` / `rawData`
9. 本期“扫描DNS”定义为 `pull`
10. 本地编辑不立即调用云端 API
11. 通过 `push` 将本地变更推送到云端
12. 批量修改默认按 `patch`
13. 首期支持常见记录类型
14. 列表支持 `type` 筛选和 `type/name/content` 排序

如果这个版本没有问题，我下一步就按它开始实现。

## 16. 参考资料

- Cloudflare DNS Records List:
  - https://developers.cloudflare.com/api/resources/dns/subresources/records/methods/list/
- Cloudflare DNS Records Batch:
  - https://developers.cloudflare.com/api/resources/dns/subresources/records/methods/batch/
- Cloudflare API Authentication:
  - https://developers.cloudflare.com/fundamentals/api/how-to/make-api-calls/
