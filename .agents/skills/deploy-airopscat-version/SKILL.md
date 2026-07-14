---
name: deploy-airopscat-version
description: 部署 AirOpsCat 指定版本到通过环境变量配置的生产主机并验证服务状态。用于用户要求“部署版本 x.y.z”、“发布/安装 AirOpsCat x.y.z”、或给出 cd /data/airopscat/ && ./install.sh version 这类远程部署命令时。
---

# Deploy AirOpsCat Version

按项目固定流程将 AirOpsCat 版本部署到生产主机，并在安装后验证 systemd 服务和启动日志。

不要在 skill、命令示例或回复中写死具体生产服务器地址（例如 `root@...`），这会泄露敏感运维信息。目标主机必须通过环境变量读取。

## 默认目标

- 主机环境变量：`AIROPSCAT_DEPLOY_HOST`
- 目录：`/data/airopscat`
- 安装命令：`cd /data/airopscat/ && ./install.sh <version>`
- 服务名：`airopscat`
- 预期监听：`http://127.0.0.1:567`

## 环境变量处理

1. 部署前先查找 `AIROPSCAT_DEPLOY_HOST`：

   ```bash
   printenv AIROPSCAT_DEPLOY_HOST
   ```

   在 PowerShell 中也可使用：

   ```powershell
   $env:AIROPSCAT_DEPLOY_HOST
   ```

2. 如果环境变量不存在或为空，停止部署并询问用户目标 SSH 地址。只询问必要信息，例如“请提供 AirOpsCat 生产部署 SSH 目标（格式 user@host）”。
3. 用户提供后，自行设置环境变量再继续。当前会话至少要设置进程环境变量；在 Codex 桌面/Windows 环境中可同时写入用户环境变量，方便后续复用：

   ```powershell
   $env:AIROPSCAT_DEPLOY_HOST = '<user@host>'
   [Environment]::SetEnvironmentVariable('AIROPSCAT_DEPLOY_HOST', '<user@host>', 'User')
   ```

4. 设置后再次读取并使用环境变量值。回复用户时只说“已使用环境变量中的生产主机”，不要回显完整主机地址，除非用户明确要求核对。

## 执行流程

1. 从用户请求提取版本号，保留原始格式中的数字部分，例如 `2.3.2`。
2. 按“环境变量处理”取得 `AIROPSCAT_DEPLOY_HOST`。
3. 确认 SSH 免密可用：

   ```bash
   ssh -o BatchMode=yes -o ConnectTimeout=10 "$AIROPSCAT_DEPLOY_HOST" 'echo SSH_OK && hostname && pwd'
   ```

4. 执行部署脚本：

   ```bash
   .agents/skills/deploy-airopscat-version/scripts/deploy_airopscat_version.sh <version>
   ```

5. 检查输出中的关键信号：
   - 安装脚本退出码为 0
   - `systemctl is-active airopscat` 返回 `active`
   - systemd 日志包含 `AirOpsCat <version> native` 或等价启动版本信息
   - 进程命令包含 `/data/airopscat/airopscat`

## 安全边界

- 只有用户明确要求部署某个版本时才执行真实部署。
- 不擅自改目标主机、目录、服务名或安装脚本路径；目标主机只从 `AIROPSCAT_DEPLOY_HOST` 读取。用户给出新目标时，先设置环境变量再执行。
- 如果 SSH 探测失败、安装脚本失败、服务未启动或日志版本不匹配，停止后续动作并汇报关键错误。
- 不执行回滚，除非用户明确要求。
- 不粘贴完整下载进度；向用户提炼版本、服务状态、PID、启动日志和失败原因。默认不要回显完整主机地址。

## 输出要求

- 用中文简洁说明部署是否成功。
- 成功时包含：版本号、目标主机来源（例如 `AIROPSCAT_DEPLOY_HOST`）、服务状态、PID 或启动日志中的版本信息。
- 失败时包含：失败阶段、退出码或关键错误行，以及建议的下一步检查。
