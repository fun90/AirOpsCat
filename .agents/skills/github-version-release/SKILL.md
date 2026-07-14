---
name: github-version-release
description: 发布或重新发布 GitHub 版本 tag。用于用户要求发布某个版本、重发某个版本号、强制更新 GitHub 远端 tag，或要求先检查 pom.xml 版本号、必要时先改版本并 push 代码，再发布 tag 的 GitHub 发版流程时使用。
---

# GitHub Version Release

按项目约定发布或重新发布一个版本 tag。

## 目标

确保 `pom.xml` 版本号、目标提交和远端 tag 三者一致，并完成远端发布。

## 执行顺序

1. 检查 `pom.xml` 中的版本号是否与目标版本一致：
   - 一致：继续后续流程
   - 不一致：先修改 `pom.xml` 到目标版本，提交并 push 代码，再继续发布
2. 检查当前分支、上游分支和工作区状态。
3. 检查本地 `HEAD`、本地 tag、远端 tag 当前分别指向哪里。
4. 如果当前分支有未 push 提交，先 push 当前分支。
5. 将目标 tag 更新到应发布的提交，默认使用当前 `HEAD`。
6. 强制推送该 tag 到远端。
7. 再次校验远端 tag 是否已指向预期提交。

## 默认规则

- 未明确指定提交时，使用当前分支的 `HEAD`
- 未明确指定远端时，默认使用 `origin`
- `pom.xml` 版本号不一致时，先修正版本，再进行 tag 发布
- 只更新用户指定的 tag，不顺带修改别的 tag
- 不处理未提交改动，除非用户明确要求一并提交
- 发现未跟踪文件时，只提示，不擅自加入提交

## 建议检查项

```bash
git status --short --branch
git rev-parse --abbrev-ref HEAD
git rev-parse --abbrev-ref --symbolic-full-name @{u}
git rev-parse HEAD
git rev-parse @{u}
git rev-list --left-right --count HEAD...@{u}
git rev-parse -q --verify refs/tags/<tag>
git ls-remote --tags origin refs/tags/<tag>
rg -n "<version>.*</version>" pom.xml
git push origin <branch>
git tag -f <tag> <commit>
git push origin refs/tags/<tag> --force
git ls-remote --tags origin refs/tags/<tag>
```

## 输出要求

- 明确说明 `pom.xml` 当前版本号，以及是否需要修改
- 如果修改了 `pom.xml`，说明已提交并 push 到哪个分支
- 明确说明当前 `HEAD` 是哪个提交
- 明确说明本地分支是否有未 push 提交
- 如果执行了 push，要说明推送的是哪个分支
- 明确说明 tag 从哪个提交改到了哪个提交
- 最后给出远端校验结果

## 异常处理

- 如果 `pom.xml` 版本号不一致且当前工作区状态不允许安全修改，先汇报并等待用户确认
- 如果当前分支没有上游分支，先说明无法自动判断是否有未 push 提交，再根据用户意图决定是否继续
- 如果推送分支失败，停止后续 tag 发布，并汇报错误
- 如果强制推送 tag 失败，汇报错误并保留现场，不做额外 Git 清理
- 如果远端校验不一致，再查一次本地 tag 与远端 tag，确认是否推错远端或提交

## 响应风格

- 用中文简洁汇报
- 先说检查结果，再说执行结果
- 不要把完整命令输出原样大段贴给用户，只提炼关键信息
