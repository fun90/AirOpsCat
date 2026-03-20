# AirOpsCat

AirOpsCat is a lightweight server and proxy management system built with Quarkus and Java 21. It provides user management, server management, node deployment, subscription generation, scheduled tasks, and notification support for proxy service providers.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.24.4-brightgreen.svg)](https://quarkus.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

![PC Preview](./docs/pc-preview-1.png)

## Overview

- Backend: Quarkus 3.24.4, Java 21
- Database: MySQL with Hibernate ORM and Panache
- Template engine: Qute
- Security: Quarkus Security with form login and role-based access control
- Remote operations: JSch-based SSH integration
- Packaging: Fast JAR and GraalVM native image

## Main Capabilities

- User and role management for `ADMIN`, `PARTNER`, and `VIP`
- Account lifecycle management with traffic statistics and expiration handling
- Server management through SSH
- Node and inbound management for VLESS, Shadowsocks, and SOCKS
- Subscription generation for client distribution
- Scheduled jobs for traffic collection, cleanup, backup, and notifications
- Bark integration for push notifications

## Project Structure

```text
src/main/java/com/fun90/airopscat
+-- config        Application bootstrap and runtime configuration
+-- controller    Web pages and REST endpoints
+-- model         Entities, DTOs, enums, and view objects
+-- repository    Panache repositories
+-- security      Authentication and authorization helpers
+-- service       Business logic, deployment, SSH, and protocol strategies
+-- util          Shared utilities
```

Important entrypoints:

- Main application: `src/main/java/com/fun90/airopscat/AirOpsCatApplication.java`
- Home/dashboard controller: `src/main/java/com/fun90/airopscat/controller/HomeController.java`
- Startup data initialization: `src/main/java/com/fun90/airopscat/config/DataInitializationConfig.java`
- Runtime configuration: `src/main/resources/application.properties`

## Requirements

### Runtime

- Java 21 or newer for JAR mode
- MySQL 8.x or compatible MySQL service

### Build

- GraalVM with `native-image` installed if you want to build the native executable
- Maven is optional because the project includes Maven Wrapper

## Quick Start

### 1. Configure the database

The app reads database settings from environment variables by default:

```properties
DB_HOST=localhost
DB_PORT=3306
DB_NAME=airopscat
DB_USERNAME=root
DB_PASSWORD=123456
```

You can also override Quarkus properties directly with an external `application.properties`.

### 2. Run in development mode

Windows:

```powershell
.\mvnw.cmd quarkus:dev
```

Linux/macOS:

```bash
./mvnw quarkus:dev
```

The dev console is available at [http://localhost:8080/q/dev](http://localhost:8080/q/dev).

### 3. Build and run the application

Windows:

```powershell
.\mvnw.cmd clean package
java -jar target/quarkus-app/quarkus-run.jar
```

Linux/macOS:

```bash
./mvnw clean package
java -jar target/quarkus-app/quarkus-run.jar
```

### 4. Run with external configuration

```bash
java -Dquarkus.config.locations=./application.properties -jar target/quarkus-app/quarkus-run.jar
```

## Native Build

Build the native executable with GraalVM:

Windows:

```powershell
.\mvnw.cmd package -Pnative -DskipTests
```

Linux/macOS:

```bash
./mvnw package -Pnative -DskipTests
```

The native profile also includes additional image build arguments from `pom.xml`.

## Tests

Run tests with Maven Wrapper:

Windows:

```powershell
.\mvnw.cmd test
```

Linux/macOS:

```bash
./mvnw test
```

Current test coverage is still light. At the time of writing, the repository includes:

- `src/test/java/com/fun90/airopscat/service/deployment/CoreConfigBuilderTest.java`
- `src/test/java/com/fun90/airopscat/service/ssh/pool/SshPoolServiceTest.java`

## Default Seed Data

On startup, if no `ADMIN` user exists, the application seeds default users:

- `admin@airopscat.com` / `admin123`
- `partner@airopscat.com` / `partner123`
- `vip@airopscat.com` / `vip123`

These defaults come from `DataInitializationConfig.java` and should be changed immediately in any non-local environment.

## Configuration Notes

The main application configuration lives in `src/main/resources/application.properties`.

Notable defaults in the current codebase:

- HTTP host: `127.0.0.1`
- HTTP port: `8080`
- Database kind: `mysql`
- Schema management: `update`
- Form login is enabled
- CORS is enabled
- Subscription base URL default: `http://localhost:8080/subscribe`

Custom application properties use the `airopscat.*` namespace, including:

- `airopscat.crypto.secret-key`
- `airopscat.subscription.url`
- `airopscat.bark.url`
- `airopscat.backup.*`
- `airopscat.install.remote-work-dir`

## Deployment Notes

- The repository includes Docker-related files under `src/main/docker`.
- Assembly and install assets live under `src/main/assembly` and `src/main/resources/config/install`.
- The application contains scheduled jobs for backup, cleanup, traffic collection, account expiration handling, and notifications. Be careful when changing related services because those changes can affect background processing as well as request handling.

## Security Warning

The current repository contains development-friendly defaults in versioned files, including database credentials, seed credentials, and other secret-like values. Treat them as local defaults only and override them in real environments through external configuration or environment variables.

## Contributing

Typical workflow:

```bash
git checkout -b feature/your-change
git commit -m "feat: describe your change"
git push origin feature/your-change
```

Recommended commit prefixes:

- `feat`
- `fix`
- `docs`
- `refactor`
- `test`
- `chore`

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
