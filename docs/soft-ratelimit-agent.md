# 用户态限速治理器

`04-soft-ratelimit-agent.sh` 提供一套不依赖 `tc`、HTB、nftables 或 iptables 的服务器端限速方案。

它通过 sing-box Clash API 周期读取 `/connections`，按连接中的 `metadata.authUser` 归属账号，基于 `/etc/airopscat/ratelimit/accounts.json` 中的账号 speed 做 token bucket 统计。账号持续超出平均速率后，治理器会通过 `DELETE /connections/{id}` 关闭该账号部分高流量连接，从而把账号长期吞吐压回配置上限附近。

## 适用场景

- 不希望再使用 HTB，避免 `htb: too many events!`、soft lockup 关联风险。
- 接受“平均速率控制”，不要求像内核队列一样平滑排队。
- 客户端通常会自动重连，短时断连可以作为超速惩罚。
- 账号限速配置仍由 AirOpsCat 同步到 `/etc/airopscat/ratelimit/accounts.json`。

## 与旧限速脚本的关系

- 旧脚本：`src/main/resources/config/shell/02-ratelimit-agent.sh`
- 新脚本：`src/main/resources/config/shell/04-soft-ratelimit-agent.sh`

两者不要同时启用。旧脚本会安装 `airopscat-ratelimit.service`，新脚本会安装 `airopscat-soft-ratelimit.service`。

新脚本复用旧配置文件路径：

```bash
/etc/airopscat/ratelimit/accounts.json
```

因此 Java 侧 `RateLimitService` 暂时无需改造，只要全局限速开关仍然负责同步账号限速配置即可。

## 安装

在节点服务器执行：

```bash
bash 04-soft-ratelimit-agent.sh
```

确认服务状态：

```bash
systemctl status airopscat-soft-ratelimit.service --no-pager
journalctl -u airopscat-soft-ratelimit.service -n 80 --no-pager
```

确认 Clash API 可读：

```bash
curl -s http://127.0.0.1:19191/connections | head
```

## 配置

默认配置写入：

```bash
/etc/default/airopscat-soft-ratelimit
```

关键项：

```bash
AIROPSCAT_SOFT_RATELIMIT_INTERVAL=3
AIROPSCAT_SOFT_RATELIMIT_BYTE_MODE=download
AIROPSCAT_SOFT_RATELIMIT_BURST_SECONDS=20
AIROPSCAT_SOFT_RATELIMIT_COOLDOWN_SECONDS=10
AIROPSCAT_SOFT_RATELIMIT_CLOSE_BATCH=4
AIROPSCAT_SOFT_RATELIMIT_MIN_CONNECTION_AGE=8
```

建议：

- `BYTE_MODE=download`：控制服务端下行到客户端，通常最符合代理限速。
- `BYTE_MODE=total`：控制上传加下载总量，惩罚更强。
- `BURST_SECONDS` 越大，短时突发越宽松。
- `COOLDOWN_SECONDS` 越小，超速账号被断开越频繁。
- `CLOSE_BATCH` 越大，超速时收敛越快，但用户体验更硬。

修改配置后重启：

```bash
systemctl restart airopscat-soft-ratelimit.service
```

## 卸载

只卸载新治理器，不影响旧 HTB 限速脚本，也不删除 `/etc/airopscat/ratelimit/accounts.json`：

```bash
bash 05-soft-ratelimit-agent-uninstall.sh
```

## 验证

观察是否有 HTB 日志：

```bash
journalctl -k -b --since "now - 1 hour" | grep -Ei "htb|soft lockup|rcu|sched: DL|clocksource|virtnet"
```

观察治理器动作：

```bash
journalctl -u airopscat-soft-ratelimit.service -f
```

出现类似日志说明账号已被限速治理：

```text
账号超速，已关闭连接: account=xxx, closed=2, deficitBytes=123456, limitBytesPerSecond=1024000
```

## 限制

这不是内核级平滑整形。它不会精确地把每个 TCP 流压到固定速率，而是通过关闭连接控制账号长期平均吞吐。

如果业务必须要求“连接不断开、吞吐严格平滑”，仍需要内核队列、eBPF/EDT 或代理内核原生限速能力。但在当前 sing-box + Clash API 条件下，这个方案的优点是低侵入、无 HTB、易回滚。
