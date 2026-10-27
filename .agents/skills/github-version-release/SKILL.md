---
name: github-version-release
description: 发布 AirOpsCat Go 正式版本：在 release/go 上提交推送、等 CI、打 vX.Y.Z tag 并核对 Release 产物。
---

# 发布 Go 版本

仅在用户明确要求发布时执行；整理代码不构成发布授权。版本只由 tag 决定（构建注入 `main.version`），不改源码版本号。重写已有 tag 需单独授权。

1. **确认分支**：`git fetch origin` 后 `git status -sb` 显示 `release/go...origin/release/go` 且无 ahead/behind。完成：分支同步，待提交文件均属本次改动。
2. **本地验证**：`go vet ./...`、`go test ./...`，再 `for a in amd64 arm64; do GOOS=linux GOARCH=$a CGO_ENABLED=0 go build -o /tmp/aoc-$a ./cmd/airopscat; done`。完成：三项全部通过，失败即停止。
3. **提交推送**：只 `git add` 本次改动文件，提交信息遵循工作区 Git 约定，`git push origin release/go`。完成：`git status -sb` 无 ahead。
4. **等 CI**：`gh run list --branch release/go --limit 1 --json databaseId,headSha` 取本次提交的运行，`gh run watch <id> --exit-status`。完成：`gh run view <id> --json conclusion -q .conclusion` 为 `success`。
5. **定版本并打 tag**：`git ls-remote --tags origin 'v*'` 取最大版本，默认补丁号加一；`git tag -a vX.Y.Z -m vX.Y.Z <提交>`，`git push origin vX.Y.Z`。
6. **核对 Release**：`gh run watch` 等 `Go release` 工作流成功，`gh release view vX.Y.Z --json assets -q '.assets[].name'`。完成：含 `airopscat-linux-amd64.tar.gz`、`airopscat-linux-arm64.tar.gz`、`checksums.txt`、`install.sh`、`airopscat.service`。

部署生产另需授权，走 `deploy-airopscat-version`；安装与回滚见 `deploy/README.md`，不得用旧安装脚本部署 Go 版本。
