# AirOpsCat 开发约定

- 业务只使用 sing-box 内核，不重新引入多内核注册表；`coreType` 仅兼容历史数据，新数据写入 `sing-box`。
- sing-box 逻辑放在 `service/singbox/`；部署逻辑按数据加载、配置构建、执行分层。
- 新增或修改用于 Qute 模板、控制器请求/响应，或经 `JsonUtil` 序列化的类时，检查并注册到 `JsonReflectionConfiguration`，确保原生镜像可用。
- 新增控制台模块前阅读 `docs/how-to-add-console-module.md`；界面复用 Tabler 组件，避免无必要的自定义样式。
- 排查运行错误时，优先查看 IDEA MCP 中的控制台日志；不可用时再用其他方式定位。

## 服务器运维脚本

修改 `../profile/airopscat/shell/` 时遵守以下约定：

- 脚本名使用唯一的两位数字前缀，按依赖顺序编号；废弃脚本从活动目录移除。
- AirOpsCat 生成的可执行脚本放 `/opt/airopscat/scripts`，持久状态放 `/var/lib/airopscat`，日志放 `/var/log/airopscat`，临时锁和 PID 放 `/run/airopscat`，下载缓存放 `/var/cache/airopscat`，专属配置按需放 `/etc/airopscat`。不要写入用户主目录或工作目录；第三方软件沿用其标准目录。
- 安装脚本幂等创建目录并设置权限，安装后清理无用缓存；cron 和 systemd 使用绝对路径，cron 条目用成对的 `# BEGIN AIROPSCAT ...` 和 `# END AIROPSCAT ...` 标记。
- 卸载只删除自身已知文件，不递归删除非空目录。
