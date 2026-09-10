# AirOpsCat 服务器配置

## 日志轮转

| 仓库文件 | 服务器安装路径 |
| --- | --- |
| `logrotate/airopscat` | `/etc/logrotate.d/airopscat` |
| `systemd/airopscat-logrotate.service` | `/etc/systemd/system/airopscat-logrotate.service` |
| `systemd/airopscat-logrotate.timer` | `/etc/systemd/system/airopscat-logrotate.timer` |

策略：

- 每日轮转，每小时检查一次。
- 最多保留 14 份，且最长保留 14 天。
- 单个日志超过 100 MB 时提前轮转。
- 压缩历史日志，使用 `copytruncate` 避免重启 AirOpsCat。

### 安装

```bash
install -o root -g root -m 0644 deploy/logrotate/airopscat \
    /etc/logrotate.d/airopscat
install -o root -g root -m 0644 deploy/systemd/airopscat-logrotate.service \
    /etc/systemd/system/airopscat-logrotate.service
install -o root -g root -m 0644 deploy/systemd/airopscat-logrotate.timer \
    /etc/systemd/system/airopscat-logrotate.timer

logrotate --debug /etc/logrotate.d/airopscat
systemd-analyze verify \
    /etc/systemd/system/airopscat-logrotate.service \
    /etc/systemd/system/airopscat-logrotate.timer
systemctl daemon-reload
systemctl enable --now airopscat-logrotate.timer
systemctl start airopscat-logrotate.service
```

### 验证

```bash
systemctl status airopscat-logrotate.timer --no-pager
systemctl show airopscat-logrotate.service -p Result
systemctl list-timers airopscat-logrotate.timer --no-pager
```

### 卸载

```bash
systemctl disable --now airopscat-logrotate.timer
rm -f /etc/systemd/system/airopscat-logrotate.timer
rm -f /etc/systemd/system/airopscat-logrotate.service
rm -f /etc/logrotate.d/airopscat
systemctl daemon-reload
```
