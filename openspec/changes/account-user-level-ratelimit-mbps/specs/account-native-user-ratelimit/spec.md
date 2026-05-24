## ADDED Requirements

### Requirement: 账号可配置原生上下行限速
系统 SHALL 允许管理员为每个账号独立配置下行（`downloadMbps`）和上行（`uploadMbps`）速度上限（单位 Mbps），字段可空表示该方向不限速。

#### Scenario: 管理员为账号设置下行限速
- **WHEN** 管理员在账号编辑弹窗中填写下行限速（Mbps）并保存
- **THEN** 系统 MUST 将 `downloadMbps` 值持久化到账号记录

#### Scenario: 管理员仅设置下行不设置上行
- **WHEN** 管理员填写 `downloadMbps` 而不填写 `uploadMbps` 并保存
- **THEN** 系统 MUST 将 `downloadMbps` 持久化，`uploadMbps` 保持为空

#### Scenario: 管理员清空限速字段
- **WHEN** 管理员将 `downloadMbps` 或 `uploadMbps` 清空并保存
- **THEN** 系统 MUST 将对应字段置为空，表示该方向不限速

### Requirement: 部署时将账号速度注入 sing-box inbound users
系统 SHALL 在构建 sing-box 配置时，将账号的 `downloadMbps` / `uploadMbps` 写入对应 inbound user 对象的 `download_mbps` / `upload_mbps` 字段。

#### Scenario: 账号设置了下行限速，部署配置包含该字段
- **WHEN** 账号的 `downloadMbps` 非空且大于 0，系统构建该账号所在节点的 sing-box 配置
- **THEN** 对应 inbound user 对象中 MUST 包含 `download_mbps` 字段，值等于账号的 `downloadMbps`

#### Scenario: 账号未设置限速，部署配置不包含限速字段
- **WHEN** 账号的 `downloadMbps` 和 `uploadMbps` 均为空，系统构建 sing-box 配置
- **THEN** 对应 inbound user 对象 MUST NOT 包含 `download_mbps` 或 `upload_mbps` 字段

#### Scenario: vless 和 hysteria2 协议均支持注入限速
- **WHEN** 节点协议为 vless、vless-reality 或 hysteria2，且账号设置了限速
- **THEN** 系统 MUST 在对应 inbound user 中写入速度字段
