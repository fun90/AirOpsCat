## ADDED Requirements

### Requirement: 已绑定 DNS 记录 SHALL 在 DNS 同步和删除操作中受保护
系统 SHALL 保证已被账户节点订阅域名绑定引用的 DNS 记录不会在 DNS 同步、手动删除、切换 DNS 服务商绑定或解绑域名 DNS 时被直接删除。系统 MUST 在需要删除已绑定 DNS 记录时提示管理员先解除相关绑定。

#### Scenario: DNS 拉取同步保留远端已不存在的已绑定记录
- **WHEN** 系统拉取某域名的远端 DNS 记录
- **AND** 本地存在某条远端结果中已不存在的 DNS 记录
- **AND** 该 DNS 记录已被账户节点订阅域名绑定引用
- **THEN** 系统 MUST 保留该 DNS 记录
- **AND** 系统 MUST 将该 DNS 记录状态标记为 `PENDING_DELETE`
- **AND** 系统 MUST 不触发数据库外键约束错误

#### Scenario: DNS 拉取同步删除远端已不存在的未绑定记录
- **WHEN** 系统拉取某域名的远端 DNS 记录
- **AND** 本地存在某条远端结果中已不存在的 DNS 记录
- **AND** 该 DNS 记录未被账户节点订阅域名绑定引用
- **THEN** 系统 MUST 删除该本地 DNS 记录

#### Scenario: DNS 拉取同步复用远端仍存在记录的本地 ID
- **WHEN** 系统拉取某域名的远端 DNS 记录
- **AND** 远端记录与本地记录通过远端记录标识匹配
- **THEN** 系统 MUST 更新既有本地 DNS 记录
- **AND** 系统 MUST 保留该 DNS 记录的本地 ID

#### Scenario: 手动删除已绑定 DNS 记录
- **WHEN** 管理员删除某条已被账户节点订阅域名绑定引用的 DNS 记录
- **THEN** 系统 MUST 拒绝删除
- **AND** 系统 MUST 提示管理员先解绑后再删除

#### Scenario: 切换或解绑包含已绑定记录的域名 DNS
- **WHEN** 管理员切换 DNS 服务商绑定或解绑域名 DNS
- **AND** 该域名下存在已被账户节点订阅域名绑定引用的 DNS 记录
- **THEN** 系统 MUST 拒绝操作
- **AND** 系统 MUST 提示管理员先解除相关绑定
