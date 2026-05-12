# 限速代理压测准备

目标：本机作为客户端，测试服务器运行完整 AirOpsCat、sing-box、限速代理真实环境，验证 200 名用户、每名用户平均 100 条连接时，限速代理 CPU 占用不超过 30%。

## 前置假设

- 测试服务器已安装最新 `02-ratelimit-agent.sh` 并启动 `airopscat-ratelimit.service`。
- 测试服务器代理入口支持 SOCKS5 或 HTTP CONNECT，并且能用账号认证触发 sing-box `/connections` 中的 `metadata.authUser`。
- 账号限速配置已同步到测试服务器 `/etc/airopscat/ratelimit/accounts.json`。
- 本机到测试服务器网络稳定，本机文件句柄上限足够承载 20000 条连接。

不建议用 `wrk`、`ab` 或 `iperf3` 直接替代本压测器：它们可以制造吞吐或 HTTP 压力，但默认不能按 200 个真实账号建立认证代理连接，也就不能覆盖限速代理最关键的 `authUser -> mark -> tc class` 路径。

## 本机准备

查看当前文件句柄上限：

```bash
ulimit -n
```

建议在当前终端临时调高：

```bash
ulimit -n 65535
```

准备账号文件，例如 `/tmp/ratelimit-accounts.json`：

```json
[
  {"username": "account001", "password": "password001"},
  {"username": "account002", "password": "password002"}
]
```

如果代理入口只校验用户名，`password` 可以留空：

```json
[
  {"username": "account001", "password": ""}
]
```

## 服务器准备

确认服务运行：

```bash
systemctl status airopscat-ratelimit.service --no-pager
journalctl -u airopscat-ratelimit.service -n 80 --no-pager
```

确认规则已创建：

```bash
nft list table inet airopscat_ratelimit
tc -s qdisc show dev eth0
tc -s class show dev eth0
```

如果默认网卡不是 `eth0`，用实际出口网卡替换。

压测时建议另开 SSH 窗口采集 CPU：

```bash
pidstat -p "$(pidof -s python3)" 1
```

如果服务器没有 `pidstat`：

```bash
top -H -p "$(pgrep -f /usr/local/bin/airopscat-ratelimit-agent | head -1)"
```

同时观察 nft map 和 tc class 规模：

```bash
watch -n 5 'nft list map inet airopscat_ratelimit tcp4 2>/dev/null | wc -l; nft list map inet airopscat_ratelimit udp4 2>/dev/null | wc -l; tc class show dev eth0 | wc -l'
```

## 执行压测

本机已通过 Clash 虚拟网卡把流量转到测试服务器时，使用 direct 模式：

```bash
python3 scripts/ratelimit_loadtest.py \
  --protocol direct \
  --connections-per-account 2000 \
  --target-host cp.cloudflare.com \
  --target-port 80 \
  --duration 120 \
  --ramp-seconds 60 \
  --request-interval 0
```

SOCKS5 示例：

```bash
python3 scripts/ratelimit_loadtest.py \
  --proxy-host TEST_SERVER_IP \
  --proxy-port TEST_PROXY_PORT \
  --protocol socks5 \
  --accounts-file /tmp/ratelimit-accounts.json \
  --connections-per-account 100 \
  --target-host cp.cloudflare.com \
  --target-port 80 \
  --duration 900 \
  --ramp-seconds 180 \
  --request-interval 10
```

HTTP CONNECT 示例：

```bash
python3 scripts/ratelimit_loadtest.py \
  --proxy-host TEST_SERVER_IP \
  --proxy-port TEST_PROXY_PORT \
  --protocol http-connect \
  --accounts-file /tmp/ratelimit-accounts.json \
  --connections-per-account 100 \
  --target-host cp.cloudflare.com \
  --target-port 80 \
  --duration 900 \
  --ramp-seconds 180 \
  --request-interval 10
```

200 个账号时，`--connections-per-account 100` 会创建 20000 条代理连接。先用 5 个账号小跑确认认证和连接路径正确，再提升到 50、100、200 个账号。

## 判定标准

- `airopscat-ratelimit-agent` 进程 CPU 长时间低于 30%。
- `connect_failed` 在升压后不持续增长。
- `request_failed` 不持续增长，少量目标站主动断连可接受。
- `journalctl -u airopscat-ratelimit.service` 没有持续刷屏错误。
- `tc class` 数量接近活跃账号数，而不是连接数。
- `nft map` 元素数量接近活跃连接数，随连接关闭逐步下降。

## 需要记录

- 测试服务器规格：CPU 型号、核心数、内存。
- 代理协议和端口。
- 账号数、每账号连接数、持续时间、升压时间。
- 限速代理 CPU 峰值和稳态值。
- sing-box CPU 峰值和稳态值。
- 连接失败数、请求失败数。
