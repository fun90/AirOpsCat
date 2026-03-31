# DNS记录功能实现计划

## 1. 目标

基于 [dns-record-design.md](./dns-record-design.md) 落地 DNS 记录功能，首期范围限定为：

1. 引入独立的 `DNS服务商` 管理能力
2. 域名可绑定并切换 DNS 服务商
3. 支持 Cloudflare 记录 `pull`
4. 支持本地编辑 DNS 记录
5. 支持 Cloudflare 批量 `push`

本计划默认遵循以下约束：

1. 保持现有 Quarkus + Qute + petite-vue 实现风格
2. 优先复用现有控制器、服务、表格、弹窗模式
3. 本地编辑不立即调用云端 API
4. 通过显式 `pull` / `push` 完成同步
5. 新增控制器请求/响应、JSON DTO、实体后同步检查 `JsonReflectionConfiguration`

## 2. 实施策略

建议按 5 个阶段推进，每个阶段都保证可编译、可验证、可回归。

阶段顺序：

1. 基础模型与菜单骨架
2. DNS 服务商管理
3. 域名绑定与记录拉取
4. 本地记录编辑与状态维护
5. 云端推送与收尾验证

## 3. 前置决策

开始编码前先固定以下实现决策，避免中途返工。

### 3.1 绑定关系实现

建议采用设计文档中的方案 A：

在 `Domain` 上直接增加 DNS 绑定字段，例如：

- `dnsProviderConfigId`
- `dnsProviderType`
- `dnsSyncStatus`
- `dnsLastSyncTime`
- 域名侧扩展字段，用于保存 Cloudflare `zoneId`

原因：

1. 首期一个域名只绑定一个服务商
2. 实现成本明显低于额外新建绑定表
3. 更容易复用当前域名列表查询逻辑

### 3.2 zoneId 存放位置

Cloudflare 的 `zoneId` 放在域名绑定侧，不放在 `DnsProviderConfig`。

建议首期实现为：

1. 如果 `Domain` 已有合适扩展字段，可直接复用
2. 如果没有，则新增 `dnsBindingExtensionJson`

### 3.3 菜单与页面命名

统一采用以下命名：

- 菜单名：`DNS服务商`
- 页面路由：`/console/device/dns-provider`
- API 前缀：`/api/admin/dns-provider-configs`

## 4. 阶段计划

## 4.1 阶段一：基础模型与菜单骨架

目标：

建立后续开发所依赖的实体、枚举、页面注册和基础目录结构。

改动清单：

1. 新增实体 `DnsProviderConfig`
2. 为 `Domain` 增加 DNS 绑定与同步相关字段
3. 新增枚举：
   - `DnsProviderType`
   - `DnsProviderConfigStatus`
   - `DnsProviderCheckStatus`
   - `DnsSyncStatus`
   - `DnsRecordStatus`
4. 新增实体 `DomainDnsRecord`
5. 新增 Repository：
   - `DnsProviderConfigRepository`
   - `DomainDnsRecordRepository`
6. 在 `ConsolePageRegistry` 注册 `DNS服务商` 页面
7. 创建前端目录骨架：
   - `templates/device/dns-provider/`
   - `static/js/device/dns-provider.js`

需要确认的代码点：

1. `Domain` 当前字段结构是否适合直接扩展
2. JSON 列在当前项目中的已有实现方式
3. `CryptoConverter` 的现有使用方式

验收标准：

1. 项目可编译
2. 新实体和枚举可正常启动 Hibernate
3. 左侧菜单可出现 `DNS服务商`
4. 不影响现有 `设备 -> 域名` 页面

## 4.2 阶段二：DNS服务商管理

目标：

先把独立服务商配置页做完整，打通服务商配置的增删改查和连接测试。

改动清单：

1. 新增 DTO：
   - `DnsProviderConfigDto`
   - `DnsProviderConfigRequest`
2. 新增 Service：
   - `DnsProviderConfigService`
3. 新增 Controller：
   - `DnsProviderConfigController`
4. 新增页面模板：
   - `templates/device/dns-provider/content.html`
   - `templates/device/dns-provider/table.html`
   - `templates/device/dns-provider/modals.html`
5. 实现前端脚本：
   - `static/js/device/dns-provider.js`
6. 新增 Provider 抽象：
   - `DnsProviderClient`
   - `DnsProviderClientRegistry`
   - `DnsProviderConfig`
   - `DnsProviderValidationResult`
7. 新增 Cloudflare 适配基础层：
   - `CloudflareDnsRestClient`
   - `CloudflareDnsProviderClient`
8. 实现 `POST /api/admin/dns-provider-configs/{id}/test`

接口范围：

1. `GET /api/admin/dns-provider-configs`
2. `GET /api/admin/dns-provider-configs/{id}`
3. `POST /api/admin/dns-provider-configs`
4. `PUT /api/admin/dns-provider-configs/{id}`
5. `DELETE /api/admin/dns-provider-configs/{id}`
6. `POST /api/admin/dns-provider-configs/{id}/test`

实现重点：

1. `credentialJson` 加密存储
2. API Token 脱敏返回
3. 本地结构校验与远程连接测试分离
4. 连接测试结果回写 `lastCheckTime`、`lastCheckStatus`、`lastCheckMessage`

验收标准：

1. 可新增 Cloudflare 服务商配置
2. 可编辑、停用、删除未使用配置
3. 可执行连接测试并看到结果
4. 页面列表正常展示服务商基本信息

## 4.3 阶段三：域名绑定与记录拉取

目标：

让域名可以绑定 DNS 服务商，并完成首次云端拉取。

改动清单：

1. 扩展 `DomainDto`：
   - `dnsProviderConfigId`
   - `dnsProviderName`
   - `dnsProviderType`
   - `dnsSyncStatus`
   - `dnsLastSyncTime`
2. 扩展 `DomainService` 域名列表查询
3. 在域名页增加：
   - 当前DNS服务商
   - 同步状态
   - 最近同步时间
4. 新增“切换DNS服务商”弹窗
5. 新增域名绑定相关接口：
   - `GET /api/admin/domains/{domainId}/dns-provider`
   - `PUT /api/admin/domains/{domainId}/dns-provider`
   - `DELETE /api/admin/domains/{domainId}/dns-provider`
6. 新增 `DomainDnsBindingService`，或在 `DomainService` 中承接绑定逻辑
7. 新增拉取相关 DTO：
   - `DnsRecordQuery`
   - `DnsProviderRecord`
   - `DnsRecordPageResponse`
   - `DomainDnsPullResponse`
8. 新增 `DomainDnsPullService`
9. 实现 `POST /api/admin/domains/{domainId}/dns-records/pull`

Cloudflare 首期要求：

1. 绑定域名时可填写 `zoneId`
2. `pull` 时从绑定信息中读取 `zoneId`
3. 将结果 upsert 到 `DomainDnsRecord`
4. 删除云端已不存在的本地记录

实现重点：

1. 域名未绑定服务商时禁止 `pull`
2. 服务商被停用时禁止 `pull`
3. `pull` 成功后回写 `dnsSyncStatus = SYNCED`
4. `pull` 失败后回写 `dnsSyncStatus = SYNC_FAILED`

验收标准：

1. 域名列表可展示当前服务商信息
2. 域名可绑定 / 切换 Cloudflare 服务商
3. 可成功把 Cloudflare DNS 记录拉到本地
4. 拉取后本地记录可在数据库中看到

## 4.4 阶段四：本地记录编辑与状态维护

目标：

把域名页内的 DNS 记录区做成完整的本地工作区。

改动清单：

1. 新增记录层 DTO：
   - `DomainDnsRecordDto`
   - `DomainDnsRecordRequest`
   - `DomainDnsRecordBatchRequest`
   - `DomainDnsRecordBatchItemRequest`
2. 新增 `DomainDnsRecordService`
3. 新增 `DomainDnsRecordController`
4. 在域名页加入 DNS 记录区域
5. 支持以下能力：
   - 搜索
   - `type` 筛选
   - 按 `type/name/content` 排序
   - 分页
   - 单条新增
   - 单条编辑
   - 单条删除
   - 批量新增
   - 批量修改
   - 批量删除
6. 实现接口：
   - `GET /api/admin/domains/{domainId}/dns-records`
   - `POST /api/admin/domains/{domainId}/dns-records`
   - `PUT /api/admin/domains/{domainId}/dns-records/{recordId}`
   - `DELETE /api/admin/domains/{domainId}/dns-records/{recordId}`
   - `POST /api/admin/domains/{domainId}/dns-records/batch`

状态维护规则：

1. 新增记录标记为 `PENDING_CREATE`
2. 修改已同步记录标记为 `PENDING_UPDATE`
3. 删除已同步记录标记为 `PENDING_DELETE`
4. 任意本地变更后将域名 `dnsSyncStatus` 置为 `NOT_SYNCED`

实现重点：

1. 本地编辑不调用云端 API
2. 逻辑删除与真正物理删除的时机要区分
3. 切换服务商后不能误操作旧服务商记录

验收标准：

1. 域名页可查看本地 DNS 记录
2. 本地新增 / 编辑 / 删除后状态正确变化
3. 同步状态能正确显示为“未同步”

## 4.5 阶段五：云端推送与收尾验证

目标：

完成本地记录到 Cloudflare 的批量推送闭环，并补齐反射、测试与文档收尾。

改动清单：

1. 新增批量推送 DTO：
   - `DnsProviderRuntimeContext`
   - `DnsBatchChangeRequest`
   - `DnsBatchChangeItem`
   - `DnsBatchChangeResponse`
   - `DomainDnsPushResponse`
2. 新增 `DomainDnsPushService`
3. 实现 `POST /api/admin/domains/{domainId}/dns-records/push`
4. 在 `CloudflareDnsProviderClient` 中实现 `/batch` 请求映射
5. push 成功后自动触发一次 pull
6. 更新 `JsonReflectionConfiguration`
7. 补充必要的错误处理与提示文案
8. 补充开发验证步骤与使用说明

推送规则：

1. `PENDING_CREATE` -> `posts`
2. `PENDING_UPDATE` -> `patches`
3. `PENDING_DELETE` -> `deletes`
4. 首期不默认使用 `puts`

状态维护：

1. push 开始前置 `dnsSyncStatus = PUSHING`
2. push 成功并 pull 完成后置 `SYNCED`
3. push 失败置 `SYNC_FAILED`

验收标准：

1. 本地待推送记录可成功写入 Cloudflare
2. push 后本地快照可自动刷新
3. 同步状态与最近同步时间正确更新
4. 相关请求/响应类型已补齐 Native Image 反射配置

## 5. 代码改动分布建议

建议优先关注以下目录：

1. `src/main/java/com/fun90/airopscat/model/entity/`
2. `src/main/java/com/fun90/airopscat/model/dto/`
3. `src/main/java/com/fun90/airopscat/model/enums/`
4. `src/main/java/com/fun90/airopscat/repository/`
5. `src/main/java/com/fun90/airopscat/service/`
6. `src/main/java/com/fun90/airopscat/service/.../provider`
7. `src/main/java/com/fun90/airopscat/controller/`
8. `src/main/resources/templates/device/domain/`
9. `src/main/resources/templates/device/dns-provider/`
10. `src/main/resources/META-INF/resources/static/js/device/`
11. `src/main/java/com/fun90/airopscat/config/JsonReflectionConfiguration.java`

## 6. 验证计划

每阶段完成后建议执行以下验证。

### 6.1 基础验证

1. `./mvnw test`
2. `./mvnw clean package`

如果测试为空或价值有限，至少保证：

1. `./mvnw clean package` 通过
2. 应用可正常启动

### 6.2 手工验证路径

建议按这条主链路回归：

1. 新增一个 Cloudflare 服务商配置
2. 执行连接测试
3. 绑定到某个域名
4. 执行 `pull`
5. 在页面本地新增 / 修改 / 删除几条记录
6. 执行 `push`
7. 再次检查页面与 Cloudflare 云端结果是否一致

### 6.3 异常场景验证

至少覆盖：

1. 未绑定服务商时执行 `pull/push`
2. 服务商被禁用时执行 `pull/push`
3. `zoneId` 缺失
4. API Token 无效
5. push 部分失败或整体失败
6. 切换服务商后旧记录残留

## 7. 风险与注意事项

## 7.1 结构风险

1. 若直接改 `Domain` 字段过多，可能影响现有查询和页面回填
2. `DomainDnsRecord` 的状态流如果处理不清晰，后续 push 很容易出错

## 7.2 云端适配风险

1. Cloudflare `batch` 返回结构需提前确认错误处理方式
2. `zoneId` 与域名的对应关系完全依赖绑定配置，录入错误会导致拉取错误域名记录

## 7.3 Native 风险

1. 新增 DTO、实体、JSON 序列化类型时容易漏加 `JsonReflectionConfiguration`
2. 若漏配，JVM 模式可能正常，native-image 运行会出错

## 7.4 UI 风险

1. 域名页新增 DNS 区域后，表格与交互复杂度会明显上升
2. 若一次塞入过多操作，建议优先保证主流程，再逐步补批量功能体验

## 8. 建议执行顺序

如果要开始正式编码，推荐按下面顺序推进：

1. 先做阶段一和阶段二，确认服务商管理模型成立
2. 再做阶段三，把“域名绑定 + pull”主链路跑通
3. 然后做阶段四，完成本地工作区能力
4. 最后做阶段五，完成 push 闭环和反射/验证收尾

## 9. 完成定义

满足以下条件即可认为首期实现完成：

1. 菜单中存在 `设备 -> DNS服务商`
2. 可管理 Cloudflare 服务商配置并完成连接测试
3. 域名可绑定或切换 DNS 服务商
4. 域名可从 Cloudflare 拉取 DNS 记录到本地
5. 本地可编辑 DNS 记录并正确维护状态
6. 本地待同步变更可推送到 Cloudflare
7. push 后本地自动刷新为最新快照
8. Maven 构建通过
9. `JsonReflectionConfiguration` 已同步补齐
