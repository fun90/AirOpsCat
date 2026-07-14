## 1. 契约与模型

- [x] 1.1 新增 guard 在线连接明细 DTO，字段包含 `accountNo`、`clientIp`、`connectionId`、`nodeTag`、`start`
- [x] 1.2 在 `GuardSyncRequest` 增加 `onlineConnections` 字段，并兼容字段缺失的老 agent 请求
- [x] 1.3 检查并更新 `JsonReflectionConfiguration`，确保新增 guard 在线 DTO 支持原生镜像 JSON 反序列化
- [x] 1.4 明确 guard 在线上报新鲜度配置项与默认值

## 2. 中心 guard-sync 处理

- [x] 2.1 在 `OpenController.guardSync` 中调用在线刷新逻辑，并保证刷新异常不影响配额响应
- [x] 2.2 为 `AccountOnlineIpService` 增加按 `nodeIp + onlineConnections` 批量刷新在线状态的方法
- [x] 2.3 在线刷新时复用现有账号有效性校验、节点 `nodeTag` 映射、连接开始时间解析和 upsert 逻辑
- [x] 2.4 记录每个节点最近一次 guard 在线明细上报时间，用于新鲜度检查和排障

## 3. 定时任务与配置

- [x] 3.1 将 `account-online-refresh` 常规路径从中心 SSH 采集调整为 guard 上报新鲜度检查
- [x] 3.2 增加显式回退开关，仅在配置开启且节点上报过期时执行中心 Clash API 采集
- [x] 3.3 更新系统配置文案，区分 guard 在线刷新、新鲜度窗口和中心采集回退
- [x] 3.4 确认节点在线趋势采样继续从 `account_online_ip` 读取，不引入新数据源
- [x] 3.5 确认账户、节点、服务器三个维度的在线查询接口和前端调用路径保持兼容

## 4. 节点 guard agent

- [x] 4.1 修改 `02-guard-agent.sh`，从同一次 `/connections` 解析结果生成 `onlineConnections`
- [x] 4.2 保持现有 `accounts` 聚合字段不变，确保防共享配额逻辑向后兼容
- [x] 4.3 过滤缺少账号或客户端 IP 的连接明细，保留连接 ID、节点标识和连接开始时间
- [x] 4.4 更新 agent 日志，区分 Clash API 读取失败、guard-sync 失败和在线明细生成失败

## 5. 迁移与清理

- [ ] 5.1 部署中心兼容逻辑后，再逐批重装或重启节点 guard agent
- [ ] 5.2 所有节点完成升级后关闭中心 Clash API 回退采集
- [x] 5.3 清理不再使用的中心在线刷新 SSH 采集代码路径
- [x] 5.4 更新相关文档，说明在线状态刷新已并入 guard agent

## 6. 验证

- [x] 6.1 单元测试：`guard-sync` 缺少 `onlineConnections` 时仍返回配额结论
- [x] 6.2 单元测试：有效在线明细会 upsert `account_online_ip` 并映射节点
- [x] 6.3 单元测试：在线刷新异常不会阻断 `GuardSyncResponse`
- [ ] 6.4 手动验证：节点 agent 上报后账户、服务器、节点在线数量与详情正常刷新
- [ ] 6.5 手动验证：停止 agent 后在线状态自然过期，新鲜度检查能暴露异常
- [ ] 6.6 手动验证：管理端完整连接列表和断开连接仍通过按需 Clash API 工作
- [ ] 6.7 手动验证：分别按账户、节点、服务器查询在线连接，结果字段与现有页面行为保持一致
