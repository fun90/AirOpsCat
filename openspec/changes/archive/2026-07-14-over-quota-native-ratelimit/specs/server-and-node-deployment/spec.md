## MODIFIED Requirements

### Requirement: Node deployments SHALL support execution, batching, version history, restore, and core switching

系统 SHALL 在构建 sing-box inbound users 时支持外部传入运行时限速覆盖，使限速同步路径可在不触发完整部署流程的情况下重建带覆盖的配置。

#### Scenario: 带限速覆盖构建 sing-box 配置
- **WHEN** `SingBoxConfigBuilder.build()` 被调用时传入非空的限速覆盖 map（key 为 accountNo）
- **THEN** 构建器 SHALL 对 map 中存在的账号用覆盖值替换 `NodeClient` 中的 `downloadMbps`/`uploadMbps`
- **AND** 不在 map 中的账号 SHALL 保持 `NodeClient` 原始值

#### Scenario: 不传覆盖时行为不变
- **WHEN** `SingBoxConfigBuilder.build()` 被调用时未传入限速覆盖（null 或空 map）
- **THEN** 构建器 SHALL 与原有行为完全一致，使用 `NodeClient` 中的原始限速值
