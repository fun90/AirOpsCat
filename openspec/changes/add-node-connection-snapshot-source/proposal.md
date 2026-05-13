## Why

当前限速脚本和账号在线状态刷新都会读取 sing-box Clash API 的 `/connections`，连接数量达到万级后，多个功能各自拉取和解析完整连接列表会重复消耗 CPU、内存和 SSH/HTTP 开销。

本变更引入节点侧单一连接快照源，让服务器上只有一个采集进程解析 Clash API 返回，并按不同消费场景输出裁剪后的轻量快照，降低万级连接下的重复解析成本。

## What Changes

- 新增节点侧连接快照采集进程，独立轮询本机 Clash API `/connections`。
- 采集进程只提取业务需要字段，并输出按用途裁剪的快照文件：
  - 限速快照：用于本地限速脚本高频消费，只包含 `authUser`、`network`、`sourceIP`、`sourcePort`。
  - 在线状态快照：用于 AirOpsCat 刷新账号在线记录，中低频消费，只包含连接 ID、账号、客户端 IP、节点标识和连接开始时间。
- 限速脚本改为读取限速快照，不再直接访问 Clash API。
- 账号在线状态刷新改为优先读取在线状态快照；快照缺失、过期或不可读时，可保留现有 Clash API 直连方式作为降级路径。
- 当前管理页中的账号、节点和服务器在线详情继续读取 `account_online_ip` 在线记录，因此与账号在线状态刷新共用同一份在线状态快照，不新增管理页专用快照。
- 管理端按需查看完整连接详情和断开连接继续使用 Clash API，避免把完整连接详情纳入高频快照。
- 快照写入必须使用原子替换，并带有生成时间和 TTL，消费方必须拒绝使用过期快照。
- 不引入 Redis、SQLite 或额外外部依赖。

## Capabilities

### New Capabilities

- `node-connection-snapshot-source`: 定义节点侧连接快照采集、快照文件、TTL、字段裁剪和消费方读取语义。

### Modified Capabilities

- `node-online-account-visibility`: 在线账号采集来源改为优先使用节点侧在线状态快照，并保留对缺失或过期快照的降级处理。

## Impact

- 影响节点安装脚本和本地运行组件：
  - `src/main/resources/config/shell/02-ratelimit-agent.sh`
  - 新增或调整连接快照采集安装逻辑与 systemd 服务。
- 影响 Java 侧在线刷新链路：
  - `SingBoxOnlineConnectionService`
  - `AccountOnlineIpService` 的输入 DTO 或适配层。
- 影响远程读取方式：
  - AirOpsCat 需要通过 SSH 读取节点侧在线状态快照，或在降级时继续使用现有 `SingBoxClashApiClient`。
- 影响规格：
  - 新增节点连接快照源能力。
  - 调整在线账号可见性的采集来源要求。
