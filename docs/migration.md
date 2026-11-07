# 数据迁移与回滚

## 范围

运行数据库仅支持 SQLite。`../cmd/airopscat-import` 是独立的一次性 MySQL 导入工具，不属于运行服务依赖；保留它是为首次切换及重新导入服务。原始数据只读，不能覆盖现有 SQLite。

## 准备

1. 保留原服务版本、启动配置、数据库备份和旧字段加密密钥；密钥只存权限为 0600 的配置文件。
2. 停止原管理服务的采集、部署和 guard 上报处理，避免双写；节点内核继续运行。
3. 从 `release/go` 构建工具 `go build -o airopscat-import ./cmd/airopscat-import`。
4. 通过 `airopscat-import -h` 查看精确参数。MySQL DSN 使用权限为 0600 的环境配置传入，不记录终端历史或日志。旧密钥用于解开源字段，新 `AIROPSCAT_SECRET_KEY` 用于重新加密。

导入示例（变量须事先安全设置）：

```bash
./airopscat-import --mysql-dsn "$IMPORT_MYSQL_DSN" --old-secret-key "$IMPORT_OLD_SECRET" --out /var/lib/airopscat/import.db
```

工具通过命令行接收源凭据，执行时应限制主机账户访问，执行后清除环境变量。它只应用于短时受控迁移，不用于后台任务。

## 验收与切换

- SQLite 输出应为全新文件；导入失败删除该输出后重新导入，源库不受影响。
- 核对账户/用户/节点/部署位/服务器/标签/域名/流水数量、引用关系、UUID 与授权码、敏感字段解密、账户周期及金额。
- 服务器历史 host 不再作为 SSH 地址；SSH 使用 IP，订阅域名由 DNS 绑定决定。未匹配的历史域名应根据导入报告处理。
- 监控历史不导入，从新采集建立；首次累计流量采集仅建立基线，避免重复计费，须以客户端实流量核验切换区间。
- 运行默认保留期以当前 Go 版为准：账户流量和监控 30 天、备份 15 天、系统日志 90 天；模板目录空值使用进程工作目录 `./config`。切换前如需更长追溯，应先在控制台调大相应配置并规划磁盘容量，再开启任务。旧 Java 专用键不展示也不生效。
- 保存导入后的原始快照后启动服务，最初关闭自动 DNS、通知与远端采集；先验收页面与订阅，再逐项放开服务器 ID 和域名 ID 白名单。
- 正式切换后至少观察 3 天：流量差分、周期重置、限速、guard、告警、DNS 与内存。未观察完成不能记录为正式验收通过。

## 迁移 0020：封禁端口表

新增 `server_blocked_port`，记录流量防刷处置封禁的旧节点端口。回滚到不含该表的版本前，该表随数据库一并丢失，但服务器上已写入的 `iptables DROP` 规则不会自动清除，需要在对应服务器上手工删除（`iptables -D INPUT -p tcp|udp --dport <端口> -j DROP`，再 `iptables-save > /etc/iptables/rules.v4`）。

## 迁移 0021：主键自增、到期与禁用分离

- `account`、`server`、`domain` 重建为 `INTEGER PRIMARY KEY AUTOINCREMENT`，删除最大 ID 后不再复用，避免 `transactions.business_id` 把旧流水关联到新记录。自增起点取现存最大 ID 与流水中该表出现过的最大 `business_id` 的较大值；`alert_state`、`system_log` 的 `resource_id` 不参与计算。
- 迁移文件首行 `-- airopscat:foreign-keys-off` 声明需关闭外键：迁移器固定一个连接，事务外 `PRAGMA foreign_keys=OFF`，事务内建新表、复制、删旧表、改名并按原文重建索引与触发器（含其他表上引用这三张表的 `deploy_revision_account_traffic_stats_update`、`dns_auto_provider_update`），`PRAGMA foreign_key_check` 无结果才提交，结束后恢复外键，无法确认恢复时丢弃该连接。任一步失败整体回滚、服务拒绝启动，数据库保持 0020。外键开启时直接 `DROP TABLE` 会级联删除标签、绑定、流量统计、部署位等子表数据，后续重建被引用表的迁移必须使用该标记。
- `account` 新增 `expire_handled_to`，记录到期任务已处理的 `to_date`。到期任务不再写 `disabled`，`disabled` 只表示手动封禁；页面「已过期」「已禁用」独立筛选和计数，可同时成立。
- 取舍：旧到期任务会把到期账户置 `disabled=1`，无法与到期前就手动封禁的账户区分。迁移把「已到期且 `disabled=1`」的账户统一置 `disabled=0`、`expire_handled_to=to_date`；配置与订阅按 `to_date` 过滤，这些账户不会因此恢复可用，但之后续期即直接生效。升级前如需保留封禁，先导出名单，升级后对需要的账户重新禁用：

  ```sql
  SELECT id, account_no, remark, to_date FROM account WHERE disabled = 1 AND to_date <= datetime('now', '+8 hours');
  ```

- 回滚：旧版本二进制可直接使用 0021 库（新列可空，自增对写入透明），但旧到期任务会把到期账户重新置 `disabled=1`；再次升级不会重跑 0021，这些账户会显示为手动禁用，需要人工启用。需要完全还原时，使用安装器升级前的同组备份恢复。

## 回滚

安装器的自动回滚只针对已有 SQLite 服务升级：停服后备份 DB/WAL/SHM 同组文件及二进制，失败时同组恢复，具体见部署说明。

首次从旧管理系统切换后的回滚是人工流程：停止 Go 管理服务，保留其数据库及日志，恢复旧服务和原数据库。切换期间的开户、续费、DNS 和流量变化不会自动写回源库，必须逐项核对并补录。不得让两套管理服务同时部署或采集。

演练使用数据库副本、独立端口、独立目录，并设置 `AIROPSCAT_TASKS_DISABLED=1`、`AIROPSCAT_NOTIFY_DISABLED=1`，不停止现有生产服务。
