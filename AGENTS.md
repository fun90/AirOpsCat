# AGENTS.md

This file provides guidance to Codex when working in this repository.

## Language Rules（非常关键）

- 所有输出必须使用中文（简体中文）
- 包括：
    - OpenSpec 内容（proposal / spec / tasks）
    - 代码注释
    - 解释说明
    - review 内容
- 不允许使用英文作为主要语言（除非是代码或专有名词）

## OpenSpec Rules

- 所有 OpenSpec 文档必须使用中文描述
- spec.md / proposal.md / tasks.md 必须为中文
- 变量名可以英文，但说明必须中文

## Enforcement

- 如果生成内容不是中文，必须自动重写为中文

## Project Overview

AirOpsCat is a Quarkus `3.24.4` application built with Java `21`. It is a server and proxy management system for proxy service providers, with support for account management, server management, node deployment, subscription generation, scheduled tasks, and push notifications.

Current stack and runtime characteristics:

- **Backend**: Quarkus REST, CDI, Hibernate ORM, Panache, Scheduler
- **Database**: MySQL via Agroal + Hibernate ORM
- **Frontend**: Qute templates, Tabler `1.3.2`, petite-vue
- **Security**: Quarkus Security, JPA-backed users, form login, role-based access control
- **Remote operations**: JSch for SSH connectivity
- **Protocol/Core support**: Xray, sing-box, hysteria2-related inbound/core handling
- **Packaging**: Fast JAR and GraalVM native image

## Common Development Commands

### Build and Run
```bash
# Build the application
./mvnw clean package

# Run the packaged application
java -jar target/quarkus-app/quarkus-run.jar

# Run with external configuration
java -Dquarkus.config.locations=./application.properties -jar target/quarkus-app/quarkus-run.jar

# Run tests
./mvnw test

# Run a single test pattern
./mvnw test -Dtest=*ServiceTest

# Run tests with debug output
./mvnw test -X
```

### Development Mode
```bash
# Start dev mode with hot reload
./mvnw quarkus:dev

# Change HTTP port
./mvnw quarkus:dev -Dquarkus.http.port=8888

# Enable debug port
./mvnw quarkus:dev -Ddebug=5005
```

Useful dev URLs:

- App: `http://localhost:8080`
- Dev console: `http://localhost:8080/q/dev`

### Native Image Building
```bash
# Standard native build
./mvnw package -Pnative -DskipTests

# Native build with explicit extra args
./mvnw package -Pnative -DskipTests -Dquarkus.native.additional-build-args=-J-Xmx8g

# Run native executable
./target/airopscat-2.2.0-runner

# Run native executable with external configuration
./target/airopscat-2.2.0-runner -Dquarkus.config.locations=./application.properties
```

## Architecture Overview

### Core Structure

- **Main application**: `com.fun90.airopscat.AirOpsCatApplication`
- **Config**: bootstrap, Jackson, crypto, data initialization, SSH autoconfiguration
- **Controllers**: REST endpoints and page rendering controllers
- **Services**: business logic, deployment, SSH, install, traffic, expiration notification
- **Repositories**: Panache repositories for persistence access
- **Models**: entities, DTOs, enums, converters, VOs
- **Security**: authentication handler, augmentor, login failure/status helpers
- **Client**: GitHub release API client for update checks
- **Utilities**: crypto, JSON, template, config file, random, obfuscation helpers

### Important Packages

- `controller/`: admin APIs, public APIs, login/dashboard routing
- `service/deployment/`: deployment orchestration and config builders
- `service/core/strategy/`: core management strategy implementations
- `service/inbound/strategy/`: default inbound generation by core/protocol
- `service/traffic/`: traffic collectors and collector registry
- `service/expiration/`: expiration and threshold notification services
- `service/install/`: remote install script discovery and execution
- `service/ssh/`: SSH abstraction and provider implementations
- `config/`: Quarkus/Jackson/native/data init related configuration
- `model/dto/deployment/` and `model/dto/install/`: deployment/install payloads

### Key Services

- `AccountService`: account lifecycle, renewal, authorization code management
- `NodeService`: node CRUD, port checks, deployment entrypoints, core switching
- `ServerService`: server CRUD, connection testing, renewal, traffic calibration
- `SubscriptionService`: subscription URL generation and client-specific config rendering
- `NodeDeploymentService`: deployment preparation and execution
- `CoreManagementService`: delegates start/stop/restart/status operations by core type
- `ServerInstallService`: one-click install script loading and remote execution
- `ScheduledTaskService`: background jobs for expiration, traffic, and notifications
- `DatabaseBackupService`: MySQL backup, cleanup, upload, restore, download
- `AccountTrafficStatsService` / `ServerTrafficStatsService`: traffic data handling
- `BarkService`: Bark push notification integration
- `UpdateNotificationService`: GitHub release based update notification support
- `LoginLockService`: login failure tracking / lock related support

## Database and Configuration

### Database Configuration

The current codebase uses **MySQL**, not SQLite.

- `quarkus.datasource.db-kind=mysql`
- JDBC URL is built from `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- Hibernate dialect: `org.hibernate.dialect.MySQLDialect`
- Schema management: `quarkus.hibernate-orm.schema-management.strategy=update`
- Naming strategy: `CamelCaseToUnderscoresNamingStrategy`

### Main Configuration File

- Primary config: `src/main/resources/application.properties`
- Custom namespace: `airopscat.*`
- External config is supported through `-Dquarkus.config.locations=...`

### Important Custom Properties

- `airopscat.subscription.url`
- `airopscat.crypto.secret-key`
- `airopscat.bark.url`
- `airopscat.bark.device-key`
- `airopscat.sing-box.grpc.local-port`
- `airopscat.online.check-minutes`
- `airopscat.install.remote-work-dir`
- `airopscat.install.scripts.dir`
- `airopscat.backup.dir`
- `airopscat.backup.cron`
- `airopscat.backup.cleanup.cron`
- `airopscat.backup.retention-days`
- `airopscat.backup.mysqldump-path`
- `airopscat.domain`
- `airopscat.api.token`
- `airopscat.docs.url`

## Security Features

- Form-based authentication with Quarkus HTTP auth
- Session timeout configured to `30M`
- JPA-backed users and role checks
- Roles currently used: `ADMIN`, `PARTNER`, `VIP`
- Public routes include `/login`, `/subscribe/*`, `/api/open/*`, static assets, and health endpoints
- Sensitive values are encrypted/decrypted through crypto utilities and converters

## Frontend and Assets

- Qute templates live in `src/main/resources/templates`
- Static assets live in `src/main/resources/META-INF/resources/static`
- Tabler assets are vendored under `static/tabler`
- petite-vue is loaded from `static/js/petite-vue.umd.js`
- The UI is split by business areas such as `person`, `device`, `vpn`, `money`, and `system`

## API and Functional Areas

### Major Admin APIs

- `/api/admin/users`
- `/api/admin/accounts`
- `/api/admin/servers`
- `/api/admin/server-configs`
- `/api/admin/nodes`
- `/api/admin/route-rules`
- `/api/admin/tags`
- `/api/admin/domains`
- `/api/admin/transactions`
- `/api/admin/traffic-stats`
- `/api/admin/backups`
- `/api/admin/server-installs`
- `/api/admin/bark`

### Public or Shared APIs

- `/api/user`
- `/api/logout`
- `/login`
- `/subscribe/*`
- `/api/open/*`

### Main Business Capabilities

- User/account management with renew, enable/disable, and auth reset flows
- Server management with SSH connectivity checks and traffic calibration
- Node management with deployment, batch deployment, copy, and core switching
- Route rule and tag management
- Subscription generation for multiple client formats
- Online IP tracking and traffic statistics collection
- Scheduled expiration and traffic-threshold notifications
- Database backup management and restore flows
- One-click server installation scripts

## Protocol, Deployment, and Config Templates

- Core config templates live under `src/main/resources/config/core`
- Subscription templates live under `src/main/resources/config/subscription`
- Install scripts live under `src/main/resources/config/install`
- Deployment packaging assets live under `src/main/assembly`

Supported config templates in the repository currently include:

- Xray inbound templates for `vless`, `vless-reality`, `shadowsocks`, `socks`
- sing-box inbound templates for `vless`, `vless-reality`, `hysteria2`, `shadowtls`, `shadowsocks`, `socks`
- Base core templates for `xray` and `sing-box`

## Scheduled and Background Work

Be careful when changing the following services because behavior is split between request handling and background jobs:

- `ScheduledTaskService`
- `DatabaseBackupService`
- `AccountOnlineIpService`
- `ServerTrafficStatsService`
- `AccountTrafficStatsService`
- `service/expiration/*`

The current codebase includes scheduled jobs for:

- traffic collection
- online account cleanup/checking
- expiration notifications
- server traffic threshold notifications
- database backup and backup cleanup

## Testing Notes

- Test dependencies are configured in `pom.xml`
- The current working tree does not contain a populated `src/test` tree, so test coverage is effectively minimal at the moment
- For risky changes, prefer validating with targeted manual runs in dev mode and Maven build/test commands

## Code Patterns and Conventions

### Persistence and Models

- Entities are in `model/entity/`
- DTOs are in `model/dto/`
- Deployment/install DTOs have their own subpackages
- Enums are in `model/enums/`
- Repositories use Panache

### Service Layer

- Business logic is kept in CDI services
- Strategy registries are used to select protocol/core specific behavior
- SSH access is abstracted behind provider and connection interfaces
- Deployment logic is decomposed into data loading, config building, and execution steps

### Security and Access

- Most admin endpoints are under `/api/admin/*`
- Additional role-scoped endpoints exist under `/api/partner/*` and `/api/vip/*`
- `HomeController` mixes page rendering with role-protected dashboard data endpoints

## Repository-Specific Guidance for Agents

- Check `git status` before editing; the working tree may already contain user changes
- Do not revert unrelated modifications
- Prefer `rg` for codebase search
- Keep code style consistent with the surrounding project and follow existing naming, layering, and implementation patterns
- Prefer reusing existing methods, services, helpers, and UI patterns before introducing new implementations
- For frontend work, use existing Tabler UI components and project conventions; do not add custom styles unless the user explicitly asks for them
- If Chinese text appears garbled, stop to identify and fix the encoding issue instead of working around it
- When reading project files or inspecting project structure, prefer IDEA MCP tools before falling back to shell-based file reads
- When the user says there is a debugging error or asks to debug a failure, proactively use IDEA MCP to read the current console/error logs, locate the issue, and continue through to a fix when feasible without waiting for the user to paste logs
- Native-image compatibility is mandatory for reflection-based JSON usage
- When adding or modifying a class, you MUST register it in `JsonReflectionConfiguration` if it matches any of these cases:
- It is used as a Qute template parameter
- It is a controller request type or response type
- It is serialized to JSON through `com.fun90.airopscat.util.JsonUtil`
- Before considering a task complete, explicitly check whether any newly added or modified class matches the three cases above and update `JsonReflectionConfiguration` in the same change when required
- When adding or reorganizing a console feature module, read `docs/how-to-add-console-module.md` first and follow its module registration, template layout, and JS path conventions
- When updating documentation, verify against `pom.xml`, `application.properties`, and the actual package structure instead of older docs
- Treat `README.md` as a helpful reference, but prefer source-of-truth from code and configuration when they differ
- Be careful with configuration defaults: this repository contains development-friendly secrets and sample credentials that should not be copied into production guidance as recommendations

## Encoding Requirements

- Treat repository text files as UTF-8 by default and preserve existing UTF-8 content when editing
- Do not introduce GBK, ANSI, or other local Windows encodings when creating or modifying files
- If a file already appears garbled or uses a different legacy encoding, stop and call it out before editing instead of rewriting it blindly
- When adding Chinese text, keep the file encoding unchanged only if it is already valid UTF-8; otherwise ask before converting the file
- Respect `.editorconfig` as the source of truth for charset and line ending behavior

## Useful Paths

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
