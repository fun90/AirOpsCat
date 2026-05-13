## 1. 节点侧快照采集

- [x] 1.1 在节点安装脚本中新增 `airopscat-connection-snapshot-agent` 程序，负责访问本机 Clash API `/connections`
- [x] 1.2 为采集程序增加环境配置，包含 Clash API 端口、采集间隔、快照目录、限速快照 TTL、在线状态快照 TTL
- [x] 1.3 实现 Clash API 响应解析，只提取限速和在线状态需要的字段
- [x] 1.4 实现 `/run/airopscat/ratelimit-flows.json` 输出，字段限定为 `network`、`sourceIP`、`sourcePort`、`authUser`
- [x] 1.5 实现 `/run/airopscat/online-connections.json` 输出，字段限定为连接 ID、账号、客户端 IP、节点标识和连接开始时间
- [x] 1.6 实现临时文件加原子替换写入，确保正式快照不会出现半写入内容
- [x] 1.7 安装并启用 `airopscat-connection-snapshot.service`，设置随系统启动和失败自动重启

## 2. 限速脚本消费快照

- [x] 2.1 将限速脚本的连接数据来源从直接访问 Clash API 改为读取 `/run/airopscat/ratelimit-flows.json`
- [x] 2.2 为限速脚本增加快照 schema、生成时间和 TTL 校验
- [x] 2.3 快照缺失、不可读或过期时记录限频日志，并避免使用过期快照作为新连接事实
- [x] 2.4 保持现有 nft map 和 tc class 同步逻辑，只替换连接来源适配层

## 3. Java 在线状态刷新消费快照

- [x] 3.1 新增节点在线状态快照 DTO 或适配模型，承接 `online-connections.json` 的精简字段
- [x] 3.2 在 `SingBoxOnlineConnectionService` 中新增通过 SSH 读取节点在线状态快照的逻辑
- [x] 3.3 增加快照 schema、生成时间和 TTL 校验，快照过期时拒绝用于当前在线状态
- [x] 3.4 将在线状态刷新改为优先使用节点快照，并按配置保留 Clash API 直连降级路径
- [x] 3.5 将快照连接记录转换为现有 `AccountOnlineIpService.refreshFromConnections` 可消费的数据结构，保持节点映射和去重语义
- [x] 3.6 确认账号、节点和服务器管理页在线详情继续读取 `account_online_ip` 在线记录，并复用由 `online-connections.json` 刷新的数据

## 4. 配置与可观测性

- [x] 4.1 增加系统配置或默认常量，控制在线状态快照优先读取和 Clash API 降级开关
- [x] 4.2 在节点侧采集程序日志中输出采集连接数、快照写入结果和失败原因
- [x] 4.3 在 Java 侧日志中输出快照读取失败、过期、降级到 Clash API 的原因
- [x] 4.4 确保快照目录和文件权限限制为 root 或 AirOpsCat 相关用户组可读

## 5. 验证

- [x] 5.1 为快照字段裁剪、TTL 校验和过期拒绝逻辑补充单元测试或脚本级验证
- [x] 5.2 验证万级连接合成数据下采集程序可以稳定生成两类精简快照
- [x] 5.3 验证限速脚本基于 `ratelimit-flows.json` 仍能正确维护 nft map 和 tc class
- [x] 5.4 验证在线状态刷新基于 `online-connections.json` 仍能写入账号、客户端 IP、连接 ID 和节点信息
- [x] 5.5 验证快照缺失或过期时 Java 侧按配置降级到 Clash API，限速侧不会重新直接访问 Clash API
- [x] 5.6 验证账号、节点和服务器管理页在线详情展示的数据来自同一批在线状态记录，不新增管理页专用快照读取链路
- [x] 5.7 运行项目测试，至少执行与 sing-box、在线状态和限速脚本相关的测试或等价手动验证
