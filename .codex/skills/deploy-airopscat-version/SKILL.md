---
name: deploy-airopscat-version
description: 部署 AirOpsCat 指定版本到项目固定生产主机并验证服务状态。用于用户要求“部署版本 x.y.z”、“发布/安装 AirOpsCat x.y.z 到 root@ssh.fun90.com”、或给出 cd /data/airopscat/ && ./install.sh version 这类远程部署命令时。
---

# Deploy AirOpsCat Version

按项目固定流程将 AirOpsCat 版本部署到生产主机 `root@ssh.fun90.com`，并在安装后验证 systemd 服务和启动日志。

## 默认目标

- 主机：`root@ssh.fun90.com`
- 目录：`/data/airopscat`
- 安装命令：`cd /data/airopscat/ && ./install.sh <version>`
- 服务名：`airopscat`
- 预期监听：`http://127.0.0.1:567`

## 执行流程

1. 从用户请求提取版本号，保留原始格式中的数字部分，例如 `2.3.2`。
2. 确认 SSH 免密可用：

   ```bash
   ssh -o BatchMode=yes -o ConnectTimeout=10 root@ssh.fun90.com 'echo SSH_OK && hostname && pwd'
   ```

3. 执行部署脚本：

   ```bash
   .codex/skills/deploy-airopscat-version/scripts/deploy_airopscat_version.sh <version>
   ```

4. 检查输出中的关键信号：
   - 安装脚本退出码为 0
   - `systemctl is-active airopscat` 返回 `active`
   - systemd 日志包含 `AirOpsCat <version> native` 或等价启动版本信息
   - 进程命令包含 `/data/airopscat/airopscat`

## 安全边界

- 只有用户明确要求部署某个版本时才执行真实部署。
- 不擅自改目标主机、目录、服务名或安装脚本路径；如果用户要求变更目标，先说明将使用的目标再执行。
- 如果 SSH 探测失败、安装脚本失败、服务未启动或日志版本不匹配，停止后续动作并汇报关键错误。
- 不执行回滚，除非用户明确要求。
- 不粘贴完整下载进度；向用户提炼版本、主机、服务状态、PID、启动日志和失败原因。

## 输出要求

- 用中文简洁说明部署是否成功。
- 成功时包含：版本号、目标主机、服务状态、PID 或启动日志中的版本信息。
- 失败时包含：失败阶段、退出码或关键错误行，以及建议的下一步检查。
