# 服务器运维脚本规范

修改 `../profile/airopscat/shell/` 时遵守：

- 脚本名使用唯一两位数字前缀，按依赖顺序编号；废弃脚本从活动目录移除。
- 可执行脚本放 `/opt/airopscat/scripts`，持久状态 `/var/lib/airopscat`，日志 `/var/log/airopscat`，临时锁和 PID `/run/airopscat`，下载缓存 `/var/cache/airopscat`，专属配置 `/etc/airopscat`；第三方软件沿用标准目录。
- 目录创建和安装须幂等，安装后清理缓存；cron/systemd 使用绝对路径；cron 以成对 `# BEGIN AIROPSCAT ...` / `# END AIROPSCAT ...` 标记。
- 卸载只删除自身已知文件，不递归删除非空目录。
