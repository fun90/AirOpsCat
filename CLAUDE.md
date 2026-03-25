# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AirOpsCat is a Quarkus 3.24.4 application built with Java 21, providing a server and proxy management system for proxy service providers. It supports account management, server management, node deployment, subscription generation, scheduled tasks, and push notifications.

- **Backend**: Quarkus REST, CDI, Hibernate ORM, Panache, Scheduler
- **Database**: MySQL via Agroal + Hibernate ORM
- **Frontend**: Qute templates, Tabler 1.3.2, petite-vue
- **Security**: Quarkus Security, JPA-backed users, form login, RBAC
- **Remote operations**: JSch for SSH connectivity
- **Protocol/Core support**: Xray, sing-box, hysteria2
- **Packaging**: Fast JAR and GraalVM native image

## Common Development Commands

```bash
# Build
./mvnw clean package

# Run
java -jar target/quarkus-app/quarkus-run.jar
java -Dquarkus.config.locations=./application.properties -jar target/quarkus-app/quarkus-run.jar

# Dev mode (hot reload)
./mvnw quarkus:dev
./mvnw quarkus:dev -Dquarkus.http.port=8888
./mvnw quarkus:dev -Ddebug=5005
# App: http://localhost:8080  Dev console: http://localhost:8080/q/dev

# Tests (minimal coverage currently)
./mvnw test
./mvnw test -Dtest=*ServiceTest

# Native build (requires GraalVM)
./mvnw package -Pnative -DskipTests -Dquarkus.native.additional-build-args=-J-Xmx8g
./target/airopscat-2.2.0-runner -Dquarkus.config.locations=./application.properties
```

## Architecture Overview

### Package Structure
- `controller/`: admin APIs, public APIs, login/dashboard routing
- `service/`: business logic; key subpackages:
  - `deployment/`: deployment orchestration and config builders
  - `core/strategy/`: core management strategy implementations (Xray, SingBox, Hysteria2)
  - `inbound/strategy/`: default inbound generation by core/protocol
  - `traffic/`: traffic collectors and collector registry
  - `expiration/`: expiration and threshold notification services
  - `install/`: remote install script discovery and execution
  - `ssh/`: SSH abstraction and provider implementations
- `repository/`: Panache repositories
- `model/`: entities (`model/entity/`), DTOs (`model/dto/`), VOs (`model/vo/`), enums (`model/enums/`)
- `config/`: bootstrap, Jackson, crypto, data initialization, SSH autoconfiguration
- `security/`: authentication handler, augmentor, login failure/status helpers
- `util/`: crypto, JSON, template, config file, random, obfuscation helpers

### Key Entity Relationships
- `Account` → `User` (many-to-one via `user_id`)
- `Node` → `Server` (many-to-one primary + optional `backup_server_id`)
- `Node` → `ServerHost` (many-to-one for access host via `access_host_id`)
- `Node` → `Node` (self-referential one-to-one for outbound chaining via `out_id`)
- `Tag` ↔ `Node`/`Account` (many-to-many via junction tables, not modeled as entities)
- `AccountTrafficStats`/`ServerTrafficStats` track periodic bandwidth usage
- `AccountOnlineIp` tracks concurrent sessions per account
- Node relationships use EAGER fetch to prevent N+1 queries

### Strategy Pattern (Core of the Service Layer)
Three registries map `CoreType` enum values to implementations:

1. **`CoreManagementStrategyRegistry`**: Lifecycle operations (start/stop/restart/install/uninstall/config) — each strategy receives an `SshConnection` for remote execution
2. **`CoreConfigBuilderRegistry`**: Assembles deployment config JSON per core type; JSON templates in `src/main/resources/config/core/`
3. **`TrafficStatsCollectorRegistry`**: Protocol-specific traffic stats; `AbstractV2RayApiTrafficStatsCollector` provides shared V2Ray API logic

Xray inbound/outbound conversion also uses a strategy pattern in `service/xray/strategy/`.

### Subscription Generation Flow
`SubscriptionController` → validates `authCode` → retrieves account-linked nodes via `TagService.getAvailableNodesByAccount()` (filters: deployed=1, disabled=0, type=PROXY) → optional `NodeObfuscator` duplicates nodes by configurable multiple → `TemplateUtil.processStringTemplate()` renders config from templates in `src/main/resources/config/subscription/` (Clash, Loon, SingBox, Shadowrocket).

### Key Services
- `AccountService`: account lifecycle, renewal, auth code management
- `NodeService`: node CRUD, port checks, deployment entrypoints, core switching
- `ServerService`: server CRUD, connection testing, renewal, traffic calibration
- `SubscriptionService`: subscription URL generation and client-specific config rendering
- `NodeDeploymentService`: deployment preparation and execution (async via `CompletableFuture`)
- `CoreManagementService`: delegates start/stop/restart/status by core type
- `ServerInstallService`: one-click install script loading and remote execution
- `ScheduledTaskService`: background jobs for expiration, traffic, notifications
- `DatabaseBackupService`: MySQL backup, cleanup, upload, restore, download
- `AccountTrafficStatsService` / `ServerTrafficStatsService`: traffic data handling
- `BarkService`: Bark push notification integration
- `UpdateNotificationService`: GitHub release based update notification
- `LoginLockService`: login failure tracking and lock support

## Database and Configuration

### Database
- `quarkus.datasource.db-kind=mysql`
- JDBC URL built from env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- Hibernate dialect: `org.hibernate.dialect.MySQLDialect`
- Schema management: `update`; naming strategy: `CamelCaseToUnderscoresNamingStrategy`

### Configuration
- Primary: `src/main/resources/application.properties`; dev overrides in `application-dev.properties`
- Custom namespace `airopscat.*` — key properties:
  - `subscription.url`, `crypto.secret-key`, `bark.url`, `bark.device-key`
  - `sing-box.grpc.local-port`, `online.check-minutes`
  - `install.remote-work-dir`, `install.scripts.dir`
  - `backup.dir`, `backup.cron`, `backup.cleanup.cron`, `backup.retention-days`, `backup.mysqldump-path`
  - `domain`, `api.token`, `docs.url`
- `RawJsonDeserializer` preserves raw JSON for inbound/rule/config fields in Xray DTOs

## Security

- Form-based authentication, session timeout 30 minutes
- Roles: `ADMIN`, `PARTNER`, `VIP` with path-based policies
- Public routes: `/login`, `/subscribe/*`, `/api/open/*`, static assets, health endpoints
- Additional role-scoped endpoints: `/api/partner/*`, `/api/vip/*`
- `CryptoConverter` JPA converter encrypts sensitive fields (SSH credentials) via AES
- `DataInitializationConfig` creates default users on first startup

## Frontend

- Qute templates: `src/main/resources/templates/` organized by domain (`vpn/`, `device/`, `person/`, `money/`, `system/`)
- Each domain typically has: `content.html`, `table.html`, `filters.html`, `modals.html`, `stats.html`
- Static assets: `src/main/resources/META-INF/resources/static/`
  - JS modules in `static/js/` with shared utilities in `common/` (DataTable.js, toast-utils, responsive-filters)
  - Tabler assets vendored under `static/tabler/`
  - petite-vue loaded from `static/js/petite-vue.umd.js`

## API Routes

### Admin APIs (`/api/admin/`)
`users`, `accounts`, `servers`, `server-configs`, `nodes`, `route-rules`, `tags`, `domains`, `transactions`, `traffic-stats`, `backups`, `server-installs`, `bark`

### Public / Shared
`/api/user`, `/api/logout`, `/login`, `/subscribe/*`, `/api/open/*`

## Config Templates and Resources

- Core config templates: `src/main/resources/config/core/`
  - Xray inbounds: `vless`, `vless-reality`, `shadowsocks`, `socks`
  - sing-box inbounds: `vless`, `vless-reality`, `hysteria2`, `shadowtls`, `shadowsocks`, `socks`
- Subscription templates: `src/main/resources/config/subscription/`
- Install scripts: `src/main/resources/config/install/`
- Packaging assets: `src/main/assembly/`

## Scheduled and Background Work

Be careful when modifying these — behavior is split between request handling and background jobs:
`ScheduledTaskService`, `DatabaseBackupService`, `AccountOnlineIpService`, `ServerTrafficStatsService`, `AccountTrafficStatsService`, `service/expiration/*`

## Critical Code Patterns

### JsonReflectionConfiguration (Native Image)
When adding or modifying a class, you **MUST** register it in `JsonReflectionConfiguration` if it matches any of:
- Used as a Qute template parameter
- Used as a controller request/response type
- Serialized to JSON through `com.fun90.airopscat.util.JsonUtil`

Always check this before marking a task complete.

### Adding a Console Module
Read `docs/how-to-add-console-module.md` first — it defines module registration, template layout, and JS path conventions.

## Encoding Requirements

- All files must be UTF-8; respect `.editorconfig` for charset and line endings
- Do not introduce GBK, ANSI, or other Windows encodings
- If a file appears garbled or uses a legacy encoding, stop and call it out before editing

## CI/CD

GitHub Actions (`.github/workflows/release.yml`) triggers on `v*.*.*` tags, builds Linux native binary via GraalVM 21, packages as tar.gz, and creates a GitHub release.

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
