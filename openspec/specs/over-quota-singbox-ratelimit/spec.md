# over-quota-singbox-ratelimit Specification

## Purpose
TBD - created by archiving change over-quota-native-ratelimit. Update Purpose after archive.
## Requirements
### Requirement: 系统 SHALL 提供 Mbps 级超配降速系统配置
系统 SHALL 在 SystemConfig 账号设置分组中提供超配降速的下行和上行 Mbps 配置项，默认值均为 1，管理员可修改。

#### Scenario: 默认超配 Mbps 降速配置初始化
- **WHEN** 系统初始化默认配置
- **THEN** `account` 配置分组 SHALL 包含 `airopscat.account.traffic-over-quota.download-mbps` 和 `airopscat.account.traffic-over-quota.upload-mbps`
- **AND** 两项默认值 SHALL 均为 `1`

#### Scenario: 读取超配 Mbps 降速配置
- **WHEN** 系统在同步 sing-box 配置时解析超配降速值
- **THEN** 系统 SHALL 从对应配置项读取整数 Mbps 值
- **AND** 当配置为空、非法或小于 1 时 SHALL 使用 `1` 作为兜底值

### Requirement: 超配账号在 sing-box 配置中的限速必须被覆写为降速值
系统 SHALL 在构建服务器 sing-box 配置时，将当前周期流量超额账号的 `download_mbps`/`upload_mbps` 覆写为系统配置的降速值；未超额账号保持账号原始限速值（可为空）。

#### Scenario: 超配账号写入降速值
- **WHEN** 系统为某服务器构建 sing-box 配置，且账号当前周期流量已超过有效配额
- **THEN** 该账号对应 inbound user 的 `download_mbps` SHALL 被设置为超配降速配置值
- **AND** 该账号对应 inbound user 的 `upload_mbps` SHALL 被设置为超配降速配置值

#### Scenario: 未超配账号保持原始限速
- **WHEN** 系统为某服务器构建 sing-box 配置，且账号当前周期流量未超过有效配额
- **THEN** 该账号对应 inbound user 的 `download_mbps` / `upload_mbps` SHALL 与账号自身配置一致（未配置则不写入字段）

#### Scenario: 账号不在超配覆盖范围内
- **WHEN** 账号没有配置有效流量配额（无限量账号）
- **THEN** 系统 SHALL 不对该账号应用超配限速覆盖

### Requirement: 超配状态变化时系统 SHALL 重推 sing-box 配置并热重载
系统 SHALL 在限速同步（syncAll / syncServer）触发时，将带有超配覆盖的 sing-box 配置推送到对应服务器并执行热重载，使限速变更实时生效，无需断开现有连接。

#### Scenario: 同步时推送 sing-box 配置
- **WHEN** `RateLimitService.syncServer()` 执行
- **THEN** 系统 SHALL 在同步 `accounts.json` 后，额外构建带覆盖的 sing-box 配置并通过 SSH 写入 `/etc/sing-box/config.json`
- **AND** 系统 SHALL 通过现有 `CoreManagementService.RELOAD` 对 sing-box 发送重载信号

#### Scenario: sing-box 重载失败时不阻断后续同步
- **WHEN** sing-box 重载返回失败或抛出异常
- **THEN** 系统 SHALL 记录错误日志
- **AND** 系统 SHALL 不中断其他服务器的同步流程

