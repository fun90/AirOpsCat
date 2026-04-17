# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在处理此代码库时提供指导。

## 语言规则（非常关键）

- 所有输出必须使用中文（简体中文）
- 包括：OpenSpec 内容（proposal / spec / tasks）、代码注释、解释说明、review 内容
- 不允许使用英文作为主要语言（除非是代码或专有名词）
- 所有 OpenSpec 文档必须使用中文描述；spec.md / proposal.md / tasks.md 必须为中文
- 如果生成内容不是中文，必须自动重写为中文

## 项目概述

AirOpsCat 是一个基于 Quarkus 3.24.4 和 Java 21 构建的应用，为代理服务提供商提供服务器与代理管理系统，支持账号管理、服务器管理、节点部署、订阅生成、定时任务和推送通知。

- **后端**：Quarkus REST、CDI、Hibernate ORM、Panache、Scheduler
- **数据库**：MySQL（通过 Agroal + Hibernate ORM）
- **前端**：Qute 模板、Tabler 1.3.2、petite-vue
- **安全**：Quarkus Security，基于 JPA 的用户认证，表单登录，RBAC
- **远程操作**：JSch（SSH 连接）
- **协议/核心支持**：仅支持 sing-box 内核；协议包含 vless、vless-reality、hysteria2、shadowtls、shadowsocks、socks
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

# 测试（当前覆盖率较低）
./mvnw test
./mvnw test -Dtest=*ServiceTest

# 原生构建（需要 GraalVM）
./mvnw package -Pnative -DskipTests -Dquarkus.native.additional-build-args=-J-Xmx8g
./target/airopscat-2.2.0-runner -Dquarkus.config.locations=./application.properties
```

## 架构概览

### 包结构
- `controller/`：管理员 API、公开 API、登录/仪表盘路由
- `service/`：业务逻辑；主要子包：
  - `deployment/`：部署编排、部署数据加载、部署版本记录
  - `core/`：核心运维编排，对外保留兼容签名，内部只调用 sing-box 运维服务
  - `traffic/`：流量统计任务周边 DTO/历史兼容内容
  - `singbox/`：sing-box 配置构建、默认入站生成、核心运维、gRPC 查询与流量采集
  - `expiration/`：到期与阈值通知服务
  - `install/`：远程安装脚本发现与执行
  - `ssh/`：SSH 抽象与提供者实现
- `repository/`：Panache 仓库
- `model/`：实体（`model/entity/`）、DTO（`model/dto/`）、VO（`model/vo/`）、枚举（`model/enums/`）
- `config/`：启动引导、Jackson、加密、数据初始化、SSH 自动配置
- `security/`：认证处理器、增强器、登录失败/状态辅助类
- `util/`：加密、JSON、模板、配置文件、随机、混淆等工具类

### 核心实体关系
- `Account` → `User`（多对一，通过 `user_id`）
- `Node` → `Server`（多对一主服务器 + 可选 `backup_server_id`）
- `Node` → `ServerHost`（多对一访问主机，通过 `access_host_id`）
- `Node` → `Node`（自引用一对一出站链路，通过 `out_id`）
- `Tag` ↔ `Node`/`Account`（通过关联表实现多对多，未建模为实体）
- `AccountTrafficStats`/`ServerTrafficStats` 记录周期性带宽使用
- `AccountOnlineIp` 跟踪每个账号的并发会话
- Node 关系使用 EAGER 加载以避免 N+1 查询

### sing-box 单内核架构
当前系统已经收敛为 sing-box 单内核，不再通过注册表在多个内核之间选择实现：

1. **`SingBoxConfigBuilder`**：组装 sing-box 部署配置 JSON；协议模板位于 `src/main/resources/config/core/sing-box-inbound-*.json`
2. **`SingBoxDefaultInboundFactory`**：按节点类型和协议生成默认入站配置
3. **`SingBoxCoreManager`**：通过 SSH 执行 sing-box 服务启动、停止、重启、状态查询和配置上传
4. **`SingBoxTrafficStatsCollector` / `SingBoxGrpcQueryClient`**：通过 sing-box gRPC API 采集用户流量

`coreType` 字段仍保留在实体和 DTO 中用于历史数据兼容，但当前业务逻辑只接受或写入 `sing-box`。

### 订阅生成流程
`SubscriptionController` → 验证 `authCode` → 通过 `TagService.getAvailableNodesByAccount()` 获取账号关联节点（过滤条件：deployed=1、disabled=0、type=PROXY）→ 可选 `NodeObfuscator` 按可配置倍数复制节点 → `TemplateUtil.processStringTemplate()` 从 `src/main/resources/config/subscription/` 中的模板渲染配置（Clash、Loon、SingBox、Shadowrocket）。

### 关键服务
- `AccountService`：账号生命周期、续期、授权码管理
- `NodeService`：节点增删改查、端口检查、部署入口、默认入站生成
- `ServerService`：服务器增删改查、连接测试、续期、流量校准
- `SubscriptionService`：订阅 URL 生成与客户端专属配置渲染
- `NodeDeploymentService`：部署准备与执行（通过 `CompletableFuture` 异步）
- `CoreManagementService`：对外保留内核运维入口，内部固定委托 sing-box 运维服务
- `ServerInstallService`：一键安装脚本加载与远程执行
- `ScheduledTaskService`：到期、流量、通知等后台定时任务
- `DatabaseBackupService`：MySQL 备份、清理、上传、恢复、下载
- `AccountTrafficStatsService` / `ServerTrafficStatsService`：流量数据处理
- `BarkService`：Bark 推送通知集成
- `UpdateNotificationService`：基于 GitHub Release 的更新通知
- `LoginLockService`：登录失败跟踪与锁定支持

## 数据库与配置

### 数据库
- `quarkus.datasource.db-kind=mysql`
- JDBC URL 由环境变量构建：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`
- Hibernate 方言：`org.hibernate.dialect.MySQLDialect`
- Schema 管理：`update`；命名策略：`CamelCaseToUnderscoresNamingStrategy`

### 配置
- 主配置：`src/main/resources/application.properties`；开发覆盖配置：`application-dev.properties`
- 自定义命名空间 `airopscat.*` —— 关键属性：
  - `subscription.url`、`crypto.secret-key`、`bark.url`、`bark.device-key`
  - `sing-box.grpc.local-port`、`online.check-minutes`
  - `install.remote-work-dir`、`install.scripts.dir`
  - `backup.dir`、`backup.cron`、`backup.cleanup.cron`、`backup.retention-days`、`backup.mysqldump-path`
  - `domain`、`api.token`、`docs.url`
- `RawJsonDeserializer` 保留 DTO 中 inbound/rule/config 字段的原始 JSON

## 安全

- 表单认证，会话超时 30 分钟
- 角色：`ADMIN`、`PARTNER`、`VIP`，基于路径的权限策略
- 公开路由：`/login`、`/subscribe/*`、`/api/open/*`、静态资源、健康检查端点
- 额外的角色限定端点：`/api/partner/*`、`/api/vip/*`
- `CryptoConverter` JPA 转换器通过 AES 加密敏感字段（SSH 凭据）
- `DataInitializationConfig` 在首次启动时创建默认用户

## 前端

- Qute 模板：`src/main/resources/templates/`，按业务域组织（`vpn/`、`device/`、`person/`、`money/`、`system/`）
- 每个域通常包含：`content.html`、`table.html`、`filters.html`、`modals.html`、`stats.html`
- 静态资源：`src/main/resources/META-INF/resources/static/`
  - JS 模块位于 `static/js/`，共享工具类在 `common/`（DataTable.js、toast-utils、responsive-filters）
  - Tabler 资源内置于 `static/tabler/`
  - petite-vue 从 `static/js/petite-vue.umd.js` 加载

## API 路由

### 管理员 API（`/api/admin/`）
`users`、`accounts`、`servers`、`server-configs`、`nodes`、`route-rules`、`tags`、`domains`、`transactions`、`traffic-stats`、`backups`、`server-installs`、`bark`

### 公开 / 共享
`/api/user`、`/api/logout`、`/login`、`/subscribe/*`、`/api/open/*`

## 配置模板与资源

- 核心配置模板：`src/main/resources/config/core/`
  - sing-box 入站：`vless`、`vless-reality`、`hysteria2`、`shadowtls`、`shadowsocks`、`socks`
- 订阅模板：`src/main/resources/config/subscription/`
- 安装脚本：`src/main/resources/config/install/`
- 打包资源：`src/main/assembly/`

## 定时与后台任务

修改这些时需谨慎 —— 行为分散在请求处理与后台任务之间：
`ScheduledTaskService`、`DatabaseBackupService`、`AccountOnlineIpService`、`ServerTrafficStatsService`、`AccountTrafficStatsService`、`service/expiration/*`

当前已有的定时任务包括：流量采集、在线账号清理/检查、到期通知、服务器流量阈值通知、数据库备份与备份清理。

## 测试说明

- 测试依赖在 `pom.xml` 中配置
- 当前 `src/test` 目录基本为空，测试覆盖率极低
- 对于高风险改动，优先在开发模式下手动验证，并结合 Maven 构建/测试命令确认

## 代码规范

### 持久化与模型
- 实体类位于 `model/entity/`
- DTO 位于 `model/dto/`；部署/安装 DTO 有各自的子包
- 枚举位于 `model/enums/`
- 仓库层使用 Panache

### 服务层
- 业务逻辑封装在 CDI 服务中
- sing-box 相关行为优先收拢到 `com.fun90.airopscat.singbox`
- SSH 访问通过提供者和连接接口抽象
- 部署逻辑拆分为数据加载、配置构建和执行三个步骤

### 安全与访问控制
- 大多数管理端点位于 `/api/admin/*`
- 额外的角色限定端点：`/api/partner/*`、`/api/vip/*`
- `HomeController` 混合了页面渲染与角色保护的仪表盘数据端点

## 关键代码规范

### JsonReflectionConfiguration（原生镜像）
新增或修改类时，如果满足以下任一条件，**必须**在 `JsonReflectionConfiguration` 中注册：
- 用作 Qute 模板参数
- 用作控制器请求/响应类型
- 通过 `com.fun90.airopscat.util.JsonUtil` 序列化为 JSON

在标记任务完成前务必检查此项。

### 新增控制台模块
请先阅读 `docs/how-to-add-console-module.md` —— 其中定义了模块注册、模板布局和 JS 路径规范。

## 操作规范

- 编辑前先检查 `git status`，工作区可能已有用户修改，不得回退无关改动
- 优先复用现有方法、服务、辅助类和 UI 模式，避免引入重复实现
- 前端工作使用现有 Tabler UI 组件和项目规范，除非用户明确要求否则不添加自定义样式
- 如果中文文本出现乱码，先定位并修复编码问题，不要绕过处理
- 读取项目文件或检查项目结构时，优先使用 IDEA MCP 工具，其次再回退到 shell 文件读取
- 用户反馈调试错误或要求调试失败时，主动使用 IDEA MCP 读取当前控制台/错误日志，定位问题并在可行时推进到修复，无需等待用户粘贴日志
- 更新文档时，以 `pom.xml`、`application.properties` 和实际包结构为准，而非旧文档
- `README.md` 可作为参考，但当代码/配置与文档冲突时，以代码/配置为准
- 注意配置默认值：本仓库包含开发友好的密钥和示例凭据，不得将其作为生产建议复制

## 编码要求

- 所有文件必须使用 UTF-8；遵守 `.editorconfig` 中的字符集和换行符设置
- 不得引入 GBK、ANSI 或其他 Windows 编码
- 如果文件出现乱码或使用旧版编码，请在编辑前停下来指出问题

## CI/CD

GitHub Actions（`.github/workflows/release.yml`）在 `v*.*.*` 标签触发时，通过 GraalVM 21 构建 Linux 原生二进制文件，打包为 tar.gz，并创建 GitHub Release。

## 常用路径

- `pom.xml`
- `src/main/resources/application.properties`
- `src/main/java/com/fun90/airopscat/AirOpsCatApplication.java`
- `src/main/java/com/fun90/airopscat/controller/HomeController.java`
- `src/main/java/com/fun90/airopscat/config/DataInitializationConfig.java`
- `src/main/java/com/fun90/airopscat/service/ScheduledTaskService.java`
- `src/main/java/com/fun90/airopscat/service/DatabaseBackupService.java`
- `src/main/java/com/fun90/airopscat/service/deployment/NodeDeploymentService.java`
- `src/main/java/com/fun90/airopscat/service/install/ServerInstallService.java`
- `docs/how-to-add-console-module.md`
