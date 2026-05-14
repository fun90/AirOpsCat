## Context

AirOpsCat 已经收敛为 sing-box 单内核，服务器本机启用 Clash API，并且现有限速脚本与 Java 在线状态刷新都依赖 `/connections` 获取连接信息。当前重复读取的问题在低连接量下不明显，但当单台服务器连接数达到万级时，完整 JSON 的生成、传输、解析和字段提取会成为周期性 CPU 与内存分配开销。

现有消费方对字段的需求并不相同：

- 限速脚本只需要 `authUser`、`network`、`sourceIP`、`sourcePort`。
- 在线状态刷新只需要连接 ID、账号、客户端 IP、节点标识和连接开始时间。
- 当前管理页账号、节点和服务器在线详情展示的是 `account_online_ip` 在线记录，可与在线状态刷新共用同一份在线状态快照。
- 管理端完整 Clash 连接详情与断开连接是按需操作，可以继续访问 Clash API。

因此，本设计把 Clash API 连接信息采集从“每个功能各自读取完整连接列表”调整为“节点侧单采集、多份按用途裁剪快照”。

## Goals / Non-Goals

**Goals:**

- 单台服务器上只有一个高频进程读取并解析 Clash API `/connections`。
- 为限速和在线状态刷新分别输出轻量快照，避免消费方解析无关字段。
- 当前管理页在线详情与账号在线状态刷新共用在线状态快照生成的入库记录，不新增管理页专用快照。
- 在万级连接下保持 3 秒限速刷新可用，并把在线状态刷新降为中低频。
- 快照具备生成时间、TTL 和原子写入语义，消费方可以判断数据是否可用。
- 保留现有 Clash API 客户端作为完整连接详情、断开连接和降级读取能力。

**Non-Goals:**

- 不引入 Redis、SQLite、消息队列或其他节点侧外部服务。
- 不把完整 Clash `/connections` 响应作为高频共享文件。
- 不改变 sing-box Clash API 监听方式和连接管理接口语义。
- 不重构 `account_online_ip` 表结构。
- 不把管理端完整连接详情改造成实时分页服务；本次只处理高频采集复用。

## Decisions

### 决策一：使用节点侧常驻快照采集进程

新增 `airopscat-connection-snapshot-agent`，作为 systemd 服务运行在每台节点服务器上。它负责按配置间隔访问 `127.0.0.1:${AIROPSCAT_CLASH_API_PORT}/connections`，解析一次完整返回，然后输出多个裁剪快照。

选择原因：

- Clash API 的完整连接列表只被解析一次，避免限速脚本和 AirOpsCat 在线刷新重复解析。
- 采集逻辑靠近 sing-box，本机 HTTP 调用比远程 SSH 隧道更稳定。
- 节点侧组件可以与现有限速 agent 一起安装、重启和排错。

备选方案：

- Java 应用内存缓存：无法直接服务节点本机限速脚本。
- Redis/SQLite：对当前需求偏重，并引入部署依赖和故障面。
- 所有功能继续直连 Clash API：实现最简单，但万级连接下重复开销随功能数量线性增加。

### 决策二：输出按用途裁剪的快照文件

采集进程输出两个主要快照：

- `/run/airopscat/ratelimit-flows.json`
- `/run/airopscat/online-connections.json`

`/run` 在主流 Linux 中为 tmpfs，适合保存重启即失效的实时状态。快照写入使用临时文件加原子 rename，避免消费方读取到半写入内容。

限速快照示例：

```json
{
  "schemaVersion": 1,
  "generatedAtEpochSeconds": 1778640000,
  "ttlSeconds": 10,
  "flows": [
    ["tcp", "1.2.3.4", 54321, "A10001"]
  ]
}
```

在线状态快照示例：

```json
{
  "schemaVersion": 1,
  "generatedAtEpochSeconds": 1778640000,
  "ttlSeconds": 60,
  "connections": [
    {
      "id": "abc",
      "accountNo": "A10001",
      "clientIp": "1.2.3.4",
      "nodeTag": "node_4",
      "start": "2026-05-13T10:00:00Z"
    }
  ]
}
```

选择原因：

- 限速高频路径不解析 `upload`、`download`、`domain`、`destinationIP` 等无关字段。
- 在线状态刷新不需要客户端源端口，也不需要 3 秒级刷新。
- 文件格式简单，便于 Bash/Python/Java 通过 SSH 读取和排错。

### 决策三：不同消费方使用不同刷新频率

- 连接快照采集进程默认每 3 秒采集一次。
- 限速脚本默认每 3 秒读取 `ratelimit-flows.json`。
- AirOpsCat 在线状态刷新默认按现有调度周期或 15-60 秒级读取 `online-connections.json`。
- 当前管理页账号、节点和服务器在线详情继续读取数据库中的在线记录，这些记录由 `online-connections.json` 刷新生成。
- 管理端完整 Clash 连接详情和断开连接继续按需调用 Clash API。

选择原因：

- 限速需要快速感知新连接的源地址与端口。
- 在线状态窗口是分钟级，不需要跟限速同频刷新。
- 当前管理页在线详情与在线状态刷新是同一类业务数据，共用快照可以避免第三份数据源。
- 完整 Clash 连接详情属于低频人工操作，不应进入高频快照。

### 决策四：保留 Clash API 降级路径

AirOpsCat 读取在线状态快照失败时，可以按配置降级到现有 `SingBoxClashApiClient.getConnections(...)`。限速脚本读取快照失败时不直接访问 Clash API，而是保留上一次有效限速状态直到 TTL 过期，避免限速脚本和采集进程同时抢占高频采集职责。

选择原因：

- Java 侧保留降级路径可以降低上线风险。
- 限速侧保持单一数据源，避免故障时恢复成重复采集。

## Risks / Trade-offs

- 万级连接下 Clash API 本身生成完整响应仍有成本 -> 只能减少 AirOpsCat 侧重复解析，不能消除 sing-box 生成 `/connections` 的基础成本。
- 快照进程故障会影响限速和在线状态刷新 -> systemd 自动重启，并通过 TTL 让消费方识别过期数据。
- `/run` 重启后清空 -> 快照属于实时状态，服务启动后重新采集即可；不把它当历史数据。
- 快照文件包含账号和客户端 IP -> 文件权限应限制为 root 或 airopscat 用户组可读，避免普通用户读取敏感信息。
- 精简快照不包含完整连接详情 -> 管理端详情继续按需访问 Clash API，避免高频路径膨胀。

## Migration Plan

1. 新增连接快照采集 agent 和 systemd 服务安装逻辑。
2. 安装或更新限速脚本时，同时安装并启动快照采集服务。
3. 将限速脚本的数据源从 Clash API 改为 `/run/airopscat/ratelimit-flows.json`。
4. Java 在线状态刷新优先通过 SSH 读取 `/run/airopscat/online-connections.json`，失败时按配置降级到现有 Clash API。
5. 确认账号、节点和服务器管理页在线详情继续从 `account_online_ip` 读取记录，无需新增管理页专用快照。
6. 观察日志中的快照生成耗时、连接数量、快照过期和降级次数。
7. 稳定后可考虑关闭 Java 侧 Clash API 降级，或仅保留手动排障开关。

回滚策略：

- 如果快照采集服务异常，可回滚限速脚本到现有直接读取 Clash API 的版本。
- Java 在线刷新保留 Clash API 降级路径，回滚时只需关闭快照优先读取配置。

## Open Questions

- 在线状态刷新默认间隔沿用现有调度，还是新增独立配置项限定为 30 秒或 60 秒。
- 是否需要在服务器维护页面展示快照生成时间、连接数量和最近错误。
- 是否需要为极端连接规模增加更紧凑的 NDJSON 或 MessagePack 格式；第一阶段先保持 JSON，便于排错。
