## MODIFIED Requirements

### Requirement: Remote installation and core management SHALL operate through server-side execution abstractions
The system SHALL support one-click install script discovery, preview, execution, and protocol-specific core management through SSH-backed remote operations. When executing maintenance scripts, the system SHALL inject server context variables including `bandwidth_day` (the day-of-month of the server's billing cycle start date, defaulting to `1` if unset) in addition to existing variables (`server_ip`, `server_host`, `server_ssh_port`, `airopscat_domain`, `airopscat_api_token`).

#### Scenario: Install script can be previewed before execution
- **WHEN** an administrator requests preview for an install script
- **THEN** the system returns the script content or prepared execution content without running it

#### Scenario: Core-specific runtime actions can be delegated
- **WHEN** the system is asked to start, stop, restart, inspect, or switch a supported core implementation
- **THEN** it routes the request through the matching core-management strategy for that node or server context

#### Scenario: bandwidth_day 变量在脚本执行时注入
- **WHEN** 系统执行运维脚本（如 `01-system-init.sh`）
- **THEN** 环境中包含 `bandwidth_day` 变量，值为该服务器 `bandwidthDate` 字段的日期中的天数
- **AND** 若服务器未配置 `bandwidthDate`，`bandwidth_day` 默认为 `1`

---

## ADDED Requirements

### Requirement: `01-system-init.sh` SHALL 安装并配置 vnstat

系统初始化脚本 `01-system-init.sh` SHALL 安装 vnstat 软件包，根据注入的 `bandwidth_day` 变量配置 `/etc/vnstat.conf` 中的 `MonthRotate`，自动探测主出口网卡并注册至 vnstat，启动并设置 vnstat 服务开机自启。

#### Scenario: vnstat 安装与 MonthRotate 配置
- **WHEN** 在服务器上执行 `01-system-init.sh`，且环境中 `bandwidth_day=15`
- **THEN** 系统安装 vnstat，将 `/etc/vnstat.conf` 中 `MonthRotate` 设为 `15`
- **AND** 启动 vnstat 服务

#### Scenario: bandwidth_day 未注入时使用默认值
- **WHEN** 在服务器上执行 `01-system-init.sh`，且环境中未设置 `bandwidth_day`
- **THEN** `MonthRotate` 设为 `1`

#### Scenario: 主出口网卡自动探测并注册
- **WHEN** `01-system-init.sh` 完成 vnstat 安装
- **THEN** 通过 `ip route get 1.1.1.1` 探测主出口网卡（探测失败时 fallback `eth0`）
- **AND** 执行 `vnstat --add -i <iface>` 注册该网卡（幂等，已存在不报错）
