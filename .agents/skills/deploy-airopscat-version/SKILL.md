---
name: deploy-airopscat-version
description: 在用户明确授权后，将指定 Go 发行版安装到配置的生产主机并验证服务。
---

# 部署指定版本

- 仅在用户明确要求部署指定版本时调用；实现安装器或准备发布不代表生产部署授权。
- 配置 `AIROPSCAT_DEPLOY_HOST` 为 SSH 目标，目标需要 root 权限。只允许版本 `vX.Y.Z` 或 `X.Y.Z`。
- 先读 `deploy/README.md`，核对 GitHub 已有正式 Go Release、checksum 与当前服务器的 SQLite 配置。
- 从仓库根执行 `.agents/skills/deploy-airopscat-version/scripts/deploy_airopscat_version.sh <version>`，该脚本使用本仓库 `deploy/install.sh`，不调用历史 `/data/airopscat/install.sh`。
- 安装器会停服、备份二进制和 SQLite DB/WAL/SHM 同组文件；启动失败自动恢复。首次迁移须按 `docs/migration.md` 另行处理。
- 完成后核对 systemd active、`/healthz` 和运行版本，不能打印包含配置或凭据的进程参数。
- 真机开发测试目标仍为 `AGENTS.md` 约定的测试服务器，不要据此扩大到其他服务器。
