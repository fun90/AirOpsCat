## ADDED Requirements

### Requirement: 节点部署快照携带账号限速信息
系统 SHALL 在构建节点部署快照时，将账号的 `downloadMbps` 和 `uploadMbps` 包含在 `NodeClient` 中，以便 sing-box 配置构建器注入速度字段。

#### Scenario: 部署快照中的 NodeClient 携带速度字段
- **WHEN** 系统为某节点构建部署快照，且该节点关联账号设置了 `downloadMbps` 或 `uploadMbps`
- **THEN** 对应 `NodeClient` 对象 MUST 包含非空的速度字段值

#### Scenario: 未设置限速的账号 NodeClient 速度字段为空
- **WHEN** 系统为某节点构建部署快照，且账号未设置 `downloadMbps` 或 `uploadMbps`
- **THEN** 对应 `NodeClient` 的速度字段 MUST 为空
