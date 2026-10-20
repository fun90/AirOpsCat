---
name: github-version-release
description: 发布 AirOpsCat Go 正式版本；检查 release/go 分支、验证状态和版本 tag。
---

# 发布 Go 版本

- 仅在用户明确要求发布指定版本时执行；整理代码不构成发布授权。
- 正式分支为 `release/go`，版本由 `vX.Y.Z` tag 与构建时的 `main.version` 决定，不修改源码版本号。
- 检查工作区与分支，运行 `go vet ./...`、`go test ./...` 和 Linux 双架构构建。
- 核对目标提交位于正式分支、CI 通过、验收边界在 `docs/acceptance.md` 中明确。
- 用户授权后才 push 分支和 tag；重写已有 tag 属于独立授权事项。
- `.github/workflows/release.yml` 只允许正式分支历史中的版本 tag 发布，产物须含 amd64/arm64 压缩包与 SHA256。
- 安装与回滚见 `deploy/README.md`。不得用旧安装脚本部署 Go 版本。
