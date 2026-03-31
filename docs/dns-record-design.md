# DNS记录功能设计

## 1. 目标

在 AirOpsCat 中增加 DNS 记录管理能力，首期只接入 Cloudflare API，但整体架构要支持未来切换不同 DNS 服务商。

当前已确认的业务约束：

1. 代码风格保持与现有项目一致
2. 尽量复用现有 `DomainController`、`DomainService`、`DataTable` 等模式
3. 功能归属“设备”模块
4. 首期“扫描DNS”不实现 Cloudflare 异步扫描接口，而是定义为“将云端记录拉取到本地”
5. 本地修改 DNS 后不立即调用云端 API
6. 通过显式 `pull` / `push` 实现云端与本地同步
7. `DNS配置` 是独立页面，但入口只能从域名记录进入

## 2. 页面与入口设计

## 2.1 设备模块菜单

设备模块左侧菜单保持为：

1. `设备 -> 域名`
2. `设备 -> 服务器`
3. `设备 -> 服务器监控`
4. `设备 -> 一键装机`

不新增独立菜单项 `DNS配置`。

## 2.2 DNS配置页面

`DNS配置` 保留独立页面，但不在左侧菜单显示。

页面路由已确认：

- `/console/device/dns-config?domainId={id}`

入口规则：

1. 只能从 `设备 -> 域名` 的某条记录进入
2. 必须先存在域名，才能管理该域名的 DNS 配置
3. 页面必须携带 `domainId`

建议在 `ConsolePageRegistry` 中新增隐藏页面：

- `uri = /device/dns-config`
- `showInMenu = false`

建议模板与脚本：

- `templates/device/dns-config/`
- `static/js/device/dns-config.js`

## 2.3 域名页面职责

`设备 -> 域名` 页面继续负责域名资产管理，同时增加 DNS 概览与入口。

域名列表新增字段：

1. `DNS服务商`
2. `同步状态`
3. `最近同步时间`

域名列表新增操作：

1. `进入DNS配置`
2. `查看DNS记录`
3. `拉取DNS记录`
4. `推送DNS记录`

操作语义：

- `进入DNS配置`
  - 跳转到 `/console/device/dns-config?domainId={id}`
- `查看DNS记录`
  - 在当前域名页内查看该域名本地 DNS 记录
- `拉取DNS记录`
  - 调用云端列表接口，将记录写入本地
- `推送DNS记录`
  - 将本地未推送变更批量推送到云端

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

这一版数据模型遵循两个原则：

1. 实体字段只保留标准业务语义
2. 服务商特有字段全部进入 JSON 扩展字段

这样可以避免把 Cloudflare 专用字段固化到表结构里，便于以后切换到其他厂商。

## 4.2 实体一：`DomainDnsConfig`

说明：

- 原设计里的 `DomainDnsProviderConfig` 命名不再使用
- 实体名称调整为 `DomainDnsConfig`

用途：

- 表示某个域名当前使用的 DNS 配置

建议字段：

- `id`
- `domainId`
- `providerType`
- `status`
- `displayName`
- `syncStatus`
- `lastSyncTime`
- `lastCheckTime`
- `credentialJson`
- `extensionJson`
- `remark`
- `createTime`
- `updateTime`

字段说明：

- `providerType`
  - 服务商类型，例如 `CLOUDFLARE`
- `status`
  - 配置状态，例如 `ENABLED`、`DISABLED`
- `displayName`
  - 页面展示名称，例如“主域名 DNS 配置”
- `syncStatus`
  - 与云端同步状态
- `credentialJson`
  - 敏感凭证 JSON，例如 `apiToken`
- `extensionJson`
  - 非敏感扩展 JSON，例如 `zoneId`、`accountId`

已确认：

- `credentialJson` 整体加密存储

建议：

- `credentialJson` 使用 `@Convert(converter = CryptoConverter.class)` 加密
- `extensionJson` 采用 JSON 列定义保存

Cloudflare 首期建议数据结构：

- `credentialJson`
  - `apiToken`

- `extensionJson`
  - `zoneId`
  - `accountId`

## 4.3 实体二：`DomainDnsRecord`

用途：

- 保存某个域名的本地 DNS 记录快照
- 支撑本地编辑、排序、筛选、未推送状态和 push 操作

建议字段：

- `id`
- `domainId`
- `dnsConfigId`
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

- `externalRecordId`
  - 云端记录 ID，属于跨厂商可复用概念
- `status`
  - 本地记录状态
- `extensionJson`
  - 服务商扩展字段，例如 Cloudflare 的 `comment`、`tags`、其他特殊设置
- `rawData`
  - 云端原始响应 JSON，用于保底兼容

## 4.4 本期不需要的实体

本期不再设计 `DomainDnsScanRecord`，因为已确认：

- “扫描DNS”不做 Cloudflare 异步扫描
- 本期只做 pull 云端记录到本地

## 4.5 枚举建议

- `DnsProviderType`
  - `CLOUDFLARE`

- `DnsConfigStatus`
  - `ENABLED`
  - `DISABLED`

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

## 5.1 配置级同步状态

`DomainDnsConfig.syncStatus` 表示域名 DNS 配置整体的云端同步状态。

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

## 5.2 本地编辑后的状态变化

你已确认：

- 本地修改后不立即调用 API
- 本地修改后未 push，`DomainDnsConfig.syncStatus` 应为“未同步”

因此建议规则：

1. 本地新增记录：
   - `DomainDnsRecord.status = PENDING_CREATE`
   - `DomainDnsConfig.syncStatus = NOT_SYNCED`

2. 本地修改记录：
   - `DomainDnsRecord.status = PENDING_UPDATE`
   - `DomainDnsConfig.syncStatus = NOT_SYNCED`

3. 本地删除记录：
   - `DomainDnsRecord.status = PENDING_DELETE`
   - `DomainDnsConfig.syncStatus = NOT_SYNCED`

说明：

- 删除时先逻辑标记 `PENDING_DELETE`
- push 成功后再真正清理或用 pull 结果覆盖

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

    DnsRecordPageResponse listRecords(DnsProviderConfig config, DnsRecordQuery query);

    DnsBatchChangeResponse batchChangeRecords(DnsProviderConfig config, DnsBatchChangeRequest request);
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

说明：

- `credentials` 对应 `credentialJson`
- `extensions` 对应 `extensionJson`

接口职责说明：

- `validateConfigStructure`
  - 只做本地结构校验
  - 例如检查 `apiToken`、`zoneId` 是否存在，字段格式是否合理

- `testConnection`
  - 调用服务商 API 做远程连通性校验
  - 例如校验 token 是否有效、zone 是否存在、权限是否足够

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
2. 从 `extensionJson` 读取 `zoneId`
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

1. `DomainDnsConfigController`
2. `DomainDnsRecordController`

### 配置控制器

路径建议：

- `/api/admin/domain-dns-configs`

建议接口：

1. `GET /api/admin/domain-dns-configs?domainId={id}`
2. `GET /api/admin/domain-dns-configs/{id}`
3. `POST /api/admin/domain-dns-configs`
4. `PUT /api/admin/domain-dns-configs/{id}`
5. `DELETE /api/admin/domain-dns-configs/{id}`
6. `POST /api/admin/domain-dns-configs/{id}/test`

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
  - 将云端记录拉取到本地
- `push`
  - 将本地未推送记录推送到云端
- `batch`
  - 只修改本地记录和本地状态，不直接调用云端 API

## 7.2 Service 设计

建议新增：

1. `DomainDnsConfigService`
2. `DomainDnsRecordService`
3. `DomainDnsPullService`
4. `DomainDnsPushService`

### `DomainDnsConfigService`

负责：

1. 配置增删改查
2. 调用 `validateConfigStructure` 做结构校验
3. 调用 `testConnection` 做连接测试
4. 脱敏返回

### `DomainDnsRecordService`

负责：

1. 本地记录查询
2. 单条新增 / 修改 / 删除
3. 批量新增 / 修改 / 删除
4. 本地记录状态维护
5. 更新配置级 `syncStatus`

### `DomainDnsPullService`

负责：

1. 调用 provider `listRecords`
2. 将云端记录写入本地
3. 清理本地已失效记录
4. 更新 `lastSyncTime`
5. 更新 `syncStatus`

### `DomainDnsPushService`

负责：

1. 读取本地 `PENDING_*` 记录
2. 构建统一批量请求
3. 调用 provider `batchChangeRecords`
4. push 成功后再执行一次 pull
5. 将配置状态改为 `SYNCED`
6. 失败时改为 `SYNC_FAILED`

## 7.3 Repository 设计

建议新增：

1. `DomainDnsConfigRepository`
2. `DomainDnsRecordRepository`

建议查询能力：

- 按 `domainId`
- 按 `dnsConfigId`
- 按 `externalRecordId`
- 按 `type`
- 按 `status`

## 8. DTO 设计

## 8.1 页面层 DTO

建议新增：

- `DomainDnsConfigDto`
- `DomainDnsConfigRequest`
- `DomainDnsRecordDto`
- `DomainDnsRecordRequest`
- `DomainDnsRecordBatchRequest`
- `DomainDnsRecordBatchItemRequest`
- `DomainDnsPullResponse`
- `DomainDnsPushResponse`

## 8.2 Provider 统一 DTO

建议新增：

- `DnsProviderConfig`
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

## 9.1 配置 DNS

流程：

1. 用户从域名列表点击“进入DNS配置”
2. 打开 `/console/device/dns-config?domainId={id}`
3. 为当前域名创建或编辑 DNS 配置
4. 保存后写入：
   - 标准字段 -> 主列
   - `apiToken` -> `credentialJson`
   - `zoneId` / `accountId` -> `extensionJson`

## 9.2 Pull 云端记录到本地

流程：

1. 用户点击“拉取DNS记录”
2. 后端根据 `domainId` 查询 `DomainDnsConfig`
3. 调用 `DnsProviderClientRegistry`
4. 使用 `CloudflareDnsProviderClient.listRecords`
5. 拉取云端记录
6. 将结果 upsert 到本地 `DomainDnsRecord`
7. 删除本地不存在于云端的旧记录
8. 更新：
   - `lastSyncTime`
   - `syncStatus = SYNCED`

接口：

- `POST /api/admin/domains/{domainId}/dns-records/pull`

## 9.3 本地编辑记录

流程：

1. 用户在本地记录区新增 / 编辑 / 删除记录
2. 后端只修改本地表
3. 按规则标记 `PENDING_*`
4. 将 `DomainDnsConfig.syncStatus = NOT_SYNCED`

说明：

- 不立即打 Cloudflare API
- 本地数据库是 push 前的工作区

## 9.4 Push 本地记录到云端

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

- `dnsProviderType`
- `dnsSyncStatus`
- `dnsLastSyncTime`

## 10.2 `DomainService` 查询增强

查询域名分页时：

1. 继续读取 `Domain`
2. 批量读取对应的 `DomainDnsConfig`
3. 回填 DNS 服务商、同步状态、最近同步时间

说明：

- 不建议把这些字段直接加回 `Domain` 实体
- 建议只扩展 DTO 层

## 11. 前端实现建议

## 11.1 域名页

继续基于：

- `templates/device/domain/*`
- `static/js/device/domain.js`

建议新增：

1. 表格三列：
   - DNS服务商
   - 同步状态
   - 最近同步时间
2. 行操作：
   - 进入DNS配置
   - 查看DNS记录
   - 拉取DNS记录
   - 推送DNS记录
3. 页内 DNS 记录区域

## 11.2 DNS配置页

新增：

- `templates/device/dns-config/content.html`
- `templates/device/dns-config/table.html`
- `templates/device/dns-config/modals.html`
- `static/js/device/dns-config.js`

页面定位：

- 只服务于当前 `domainId`
- 不做全局多域名混合列表页

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

接口返回配置时：

1. 不返回完整敏感凭证
2. 只返回是否已配置
3. 必要时返回掩码值

## 12.3 JSON 字段使用原则

建议约束：

1. 通用业务字段必须独立成列
2. 敏感信息进入 `credentialJson`
3. 服务商扩展信息进入 `extensionJson`
4. 原始响应进入 `rawData`

## 13. Native Image 与反射要求

根据仓库要求，以下新增类型若作为控制器请求/响应或 JSON 序列化类型，需要加入：

- `JsonReflectionConfiguration`

实现时大概率要补：

- `DomainDnsConfigDto`
- `DomainDnsConfigRequest`
- `DomainDnsRecordDto`
- `DomainDnsRecordRequest`
- `DomainDnsRecordBatchRequest`
- `DomainDnsPullResponse`
- `DomainDnsPushResponse`
- 统一 Provider DTO
- 新增实体

## 14. 分阶段实施建议

## 阶段一：基础模型与配置页

1. 新增 `DomainDnsConfig`
2. 新增 `DomainDnsRecord`
3. 新增隐藏页面 `/console/device/dns-config?domainId={id}`
4. 完成配置增删改查
5. 完成配置测试

## 阶段二：域名页增强与 pull

1. 域名列表新增 DNS 服务商 / 同步状态 / 最近同步时间
2. 增加“进入DNS配置”
3. 增加“查看DNS记录”
4. 增加“拉取DNS记录”
5. 完成云端记录 pull 到本地

## 阶段三：本地编辑与 push

1. 本地记录新增 / 修改 / 删除
2. 批量新增 / 批量修改 / 批量删除
3. 记录状态维护
4. `syncStatus` 维护
5. `push` 到 Cloudflare
6. push 后自动 pull 刷新本地

## 15. 最终方案总结

最终方案已经收敛为：

1. `DNS配置` 为独立页面，但没有独立菜单入口
2. 入口只能从域名记录进入
3. `DomainDnsProviderConfig` 改名为 `DomainDnsConfig`
4. 数据模型以标准业务字段为主
5. 服务商特有字段进入 `credentialJson` / `extensionJson` / `rawData`
6. 本期“扫描DNS”定义为 `pull`
7. 本地编辑不立即调用云端 API
8. 通过 `push` 将本地变更推送到云端
9. 批量修改默认按 `patch`
10. 首期支持常见记录类型
11. 列表支持 `type` 筛选和 `type/name/content` 排序

如果这个版本没有问题，我下一步就按它开始实现。

## 16. 参考资料

- Cloudflare DNS Records List:
  - https://developers.cloudflare.com/api/resources/dns/subresources/records/methods/list/
- Cloudflare DNS Records Batch:
  - https://developers.cloudflare.com/api/resources/dns/subresources/records/methods/batch/
- Cloudflare API Authentication:
  - https://developers.cloudflare.com/fundamentals/api/how-to/make-api-calls/
