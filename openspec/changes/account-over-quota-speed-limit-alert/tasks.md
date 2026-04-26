## 1. 系统配置

- [x] 1.1 在 `SystemConfigService` 的 `account` 分组新增 `airopscat.account.traffic-over-quota.speed-kb` 配置项，类型为数字，默认值 `20`，说明单位为 `KB/s`
- [x] 1.2 确认默认配置初始化会创建新配置项，并保持已有 `airopscat.ratelimit.enabled` 配置不变

## 2. 部署客户端数据调整

- [x] 2.1 修改 `DeploymentDataLoader#buildNodeClientsMap`，移除 `isWithinBandwidth(...)` 过滤，确保流量用量不再用于剔除账户
- [x] 2.2 删除或停用 `DeploymentDataLoader#isWithinBandwidth`，确保流量超额账户仍会进入节点客户端列表
- [x] 2.3 扩展 `NodeClient` 以携带有效速度字段，或确认现有下游不需要该字段时将同一有效速度计算逻辑接入 `RateLimitService`
- [x] 2.4 新增有效速度计算方法：未超额返回 `Account.speed`；超额且账户未限速时返回超额限速；超额且账户已限速时返回 `min(Account.speed, 超额限速)`
- [x] 2.5 检查 `JsonReflectionConfiguration`，如 `NodeClient` 字段变化影响 JSON 序列化或原生镜像，补充注册或验证已有注册覆盖

## 3. 超额处置服务

- [x] 3.1 新增 `AccountTrafficOverQuotaService`（或等价服务），封装当前周期用量、有效配额、超额判定、告警状态更新和配置刷新请求
- [x] 3.2 在处置服务中读取 `airopscat.account.traffic-over-quota.speed-kb`，配置为空、非法或小于 1 时使用 `20`
- [x] 3.3 确保超额处置服务不修改账户自身 `speed` 字段，`speed` 只作为部署有效速度计算的基础限速输入
- [x] 3.4 将有效配额为 `null` 或小于等于 0 的账户视为不限量，不触发超额限速

## 4. 流量统计触发

- [x] 4.1 修改 `AccountTrafficStatsService.saveOrUpdateTrafficStats()`，在新建或更新当前周期记录后调用超额处置服务
- [x] 4.2 确保超额判定使用 `AccountTrafficStats.bandwidthQuota` 优先、`Account.bandwidth` 兜底的有效配额逻辑
- [x] 4.3 确保超额边界按当前周期总用量大于等于有效配额触发，并正确换算 GB 到字节

## 5. 告警与恢复

- [x] 5.1 使用 `AlertState` 新增 `account-traffic-over-quota` 告警状态，资源类型为 `account`
- [x] 5.2 首次超额时发送 Bark 告警，内容包含账户、当前用量、有效配额和限速值
- [x] 5.3 持续超额时更新告警状态，并按最小通知间隔抑制重复通知
- [x] 5.4 当前周期用量低于有效配额或有效配额移除时，将 ACTIVE 告警状态更新为 RECOVERED，并保持账户 `speed` 不变

## 6. 配置刷新与限速同步

- [x] 6.1 在账户进入或恢复流量超额状态后，请求节点部署或 `RateLimitService.syncAll()` 等价刷新入口
- [x] 6.2 确认全局限速开关关闭时仍记录告警并发送通知，远端实际执行保持由现有限速同步逻辑控制
- [x] 6.3 调整 `RateLimitService` 输出的 `accounts.json`，如该文件仍承担限速下发职责，则使用与部署客户端一致的有效速度计算逻辑，而不是直接使用 `Account.speed`

## 7. 前端账户详情展示

- [x] 7.1 扩展 `AccountDto` 或账户详情响应，新增当前有效速度限制和是否因流量超额限速的字段
- [x] 7.2 修改 `AccountService.toDto()` 或等价映射逻辑，复用有效速度计算逻辑填充账户详情展示字段
- [x] 7.3 修改前端账户详情页面“速度限制”展示：当 `trafficOverQuotaLimited` 为 `true` 时，在速度值旁显示“流量超额限速”标识
- [x] 7.4 确认账户超额但账户自身限速更低时，“速度限制”展示账户自身限速，且不标记为流量超额限速
- [x] 7.5 检查 `JsonReflectionConfiguration`，如新增 DTO 字段或类型影响原生镜像 JSON 序列化，补充注册或验证已有注册覆盖

## 8. 测试与验证

- [x] 8.1 增加单元测试覆盖：未超额使用账户 `speed`、超额未配置账户 `speed` 时使用默认 20、配置值覆盖默认值、超额且已有更低账户限速时不被放宽
- [x] 8.2 增加单元测试覆盖：首次超额创建 ACTIVE 告警、持续超额按间隔抑制重复通知、恢复时标记 RECOVERED 且不修改账户 `speed`
- [x] 8.3 增加或调整部署数据加载测试，验证超额账户不再被过滤且速度字段根据超额状态计算
- [x] 8.4 增加账户详情 DTO/前端展示测试，验证流量超额限速标识只在 `trafficOverQuotaLimited` 为 `true` 时出现
- [x] 8.5 运行 `./mvnw test`，并按需要手动验证账户详情页面和限速配置同步输出
