# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在处理此代码库时提供指导。

## 语言规则（非常关键）

- 所有输出必须使用中文（简体中文）
- 包括：OpenSpec 内容（proposal / spec / tasks）、代码注释、解释说明、review 内容
- 不允许使用英文作为主要语言（除非是代码或专有名词）
- 如果生成内容不是中文，必须自动重写为中文

## 项目概述

AirOpsCat 为代理服务提供商提供服务器与代理管理系统，支持账号管理、服务器管理、节点部署、订阅生成、定时任务和推送通知。

- **后端**：Quarkus REST、CDI、Hibernate ORM with Panache、Scheduler
- **数据库**：MySQL
- **前端**：Qute 模板、Tabler、petite-vue
- **安全**：Quarkus Security，表单登录，RBAC
- **远程操作**：JSch（SSH）
- **核心**：仅支持 sing-box；协议包含 vless、vless-reality、hysteria2、shadowtls、shadowsocks、socks
- **打包**：Fast JAR 与 GraalVM 原生镜像

## 常用开发命令

```bash
# 构建
./mvnw clean package

# 运行
java -jar target/quarkus-app/quarkus-run.jar
java -Dquarkus.config.locations=./application.properties -jar target/quarkus-app/quarkus-run.jar

# 开发模式（热重载）
./mvnw quarkus:dev
./mvnw quarkus:dev -Dquarkus.http.port=8888
./mvnw quarkus:dev -Ddebug=5005
# 应用：http://localhost:8080  Dev 控制台：http://localhost:8080/q/dev

# 测试
./mvnw test

# 原生构建（需要 GraalVM）
./mvnw package -Pnative -DskipTests -Dquarkus.native.additional-build-args=-J-Xmx8g
```

## 架构约定

### sing-box 单内核
系统已收敛为 sing-box 单内核，**不得**重新引入多内核注册表逻辑。`coreType` 字段保留仅用于历史数据兼容，业务逻辑只写入 `sing-box`。

### 包结构
- `controller/`、`service/`、`repository/`、`model/`（entity/dto/vo/enums）、`config/`、`security/`、`util/`
- sing-box 相关逻辑收拢到 `service/singbox/`
- 部署逻辑分三层：数据加载、配置构建、执行

### 测试
`src/test` 基本为空，测试覆盖率极低。高风险改动优先在开发模式下手动验证。

## 关键代码规范

### JsonReflectionConfiguration（原生镜像，容易遗漏）
新增或修改类时，如果满足以下任一条件，**必须**在 `JsonReflectionConfiguration` 中注册：
- 用作 Qute 模板参数
- 用作控制器请求/响应类型
- 通过 `com.fun90.airopscat.util.JsonUtil` 序列化为 JSON

**标记任务完成前务必检查此项。**

### 新增控制台模块
请先阅读 `docs/how-to-add-console-module.md`，其中定义了模块注册、模板布局和 JS 路径规范。

## 操作规范

- 编辑前先检查 `git status`，工作区可能已有用户修改，不得回退无关改动
- 优先复用现有方法、服务、辅助类和 UI 模式，避免重复实现
- 前端使用现有 Tabler UI 组件，除非用户明确要求否则不添加自定义样式
- 读取项目文件或检查项目结构时，优先使用 IDEA MCP 工具，其次回退到 shell
- 用户反馈调试错误时，主动使用 IDEA MCP 读取控制台/错误日志，不等用户粘贴日志
- 代码/配置与文档冲突时，以代码/配置为准

## 服务器运维脚本目录约定

`/Users/omg/Documents/Code/VPN/profile/airopscat/shell` 中的服务器运维脚本必须遵守以下目录约定：

| 用途 | 统一目录 | 说明 |
|------|----------|------|
| 安装的可执行脚本 | `/opt/airopscat/scripts` | 仅存放由运维脚本生成、供 cron 或 systemd 调用的 AirOpsCat 脚本 |
| 持久运行状态 | `/var/lib/airopscat` | 存放游标、检查点等需要跨进程或重启保留的数据 |
| 脚本日志 | `/var/log/airopscat` | 存放 AirOpsCat 运维脚本自身的运行日志，不混入代理内核日志 |
| 临时运行数据 | `/run/airopscat` | 存放 PID、锁和可在重启后丢失的数据 |
| 下载缓存 | `/var/cache/airopscat` | 存放安装包等可重新下载的数据，安装完成后应主动删除无用缓存 |
| 系统配置 | `/etc/airopscat` | 仅在需要持久化 AirOpsCat 专属配置时使用 |

新增或修改服务器运维脚本时还必须遵守：

- 文件名统一使用两位数字前缀且编号唯一，编号必须符合依赖执行顺序；不再使用的兼容、卸载或清理脚本应从活动脚本目录移除。
- 不得把生成脚本、游标、临时文件或下载包写入 `/root`、当前工作目录或其他用户主目录。
- 第三方软件的标准目录保持不变，例如 sing-box 配置与日志、Nginx 配置、TLS 证书等，不为追求形式统一而迁移。
- cron 和 systemd 必须使用绝对路径；cron 任务应使用成对的 `# BEGIN AIROPSCAT ...`、`# END AIROPSCAT ...` 标记，便于幂等更新与精确卸载。
- 安装脚本必须幂等创建目录并设置明确权限；卸载脚本只能删除自身已知文件，目录非空时不得递归删除。

## 编码要求

- 所有文件必须使用 UTF-8；遵守 `.editorconfig` 中的字符集和换行符设置
- 不得引入 GBK、ANSI 或其他 Windows 编码
- 中文文本出现乱码时，先定位并修复编码问题，不要绕过处理
