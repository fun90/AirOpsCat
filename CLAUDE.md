# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AirOpsCat is a Quarkus 3.24.3 application built with Java 21, providing a server management system for proxy service providers. It uses SQLite as the database, Qute for templating, and supports both standard JAR and GraalVM native compilation.

## Common Development Commands

### Build and Run
```bash
# Build the project
./mvnw clean package

# Run the application
java -jar target/airopscat-1.0.2.jar

# Build native executable (requires GraalVM)
./mvnw package -Pnative -DskipTests

# Run tests
./mvnw test
```

### Development Mode
```bash
# Run in development mode with hot reload
./mvnw quarkus:dev
```

### Native Image Building
```bash
# Build native executable with optimization
./mvnw package -Pnative -DskipTests -Dquarkus.native.additional-build-args=-J-Xmx8g

# Run native executable
./target/airopscat-1.0.2-runner

# Run with external configuration
./target/airopscat-1.0.2-runner -Dquarkus.config.locations=./application.properties
```

## Architecture Overview

### Core Structure
- **Main Application**: `com.fun90.airopscat.AirOpsCatApplication`
- **Database**: SQLite with JPA/Hibernate using SQLite dialect
- **Security**: Quarkus Security with JPA integration and form-based authentication
- **Frontend**: Qute templates with Tabler UI, petite-vue, and Bootstrap 5

### Key Layers
- **Controllers** (`controller/`): REST API endpoints and web controllers
- **Services** (`service/`): Business logic layer with 18+ services
- **Repositories** (`repository/`): Data access layer using Hibernate ORM with Panache
- **Models** (`model/`): Entities, DTOs, VOs, and enums
- **Configuration** (`config/`): Quarkus configuration classes

### Important Services
- `AccountService`: Account management and lifecycle
- `NodeService`: Proxy node management (VLESS, Shadowsocks, SOCKS, Hysteria2)
- `ServerService`: Server operations and SSH connectivity
- `SubscriptionService`: Multi-platform client subscription generation
- `SshConnectionService`: SSH operations using JSch
- `BarkService`: Push notification service

### Database Configuration
- Uses SQLite with custom Hibernate dialect: `org.hibernate.community.dialect.SQLiteDialect`
- Database file: `admin.db` (configurable in application.properties)
- Auto-schema updates enabled with `quarkus.hibernate-orm.database.generation=update`

### Security Features
- BCrypt password encryption
- Session management (30-minute timeout)
- Form-based authentication with Quarkus Security
- Role-based access control (ADMIN, PARTNER, VIP)
- Sensitive data encryption using configurable secret key

## Development Notes

### Technology Stack
- **Backend**: Quarkus 3.24.3, Java 21, Quarkus Security
- **Database**: SQLite + JPA/Hibernate
- **Frontend**: Qute, Tabler UI 1.3.2, petite-vue 0.4.1
- **Build**: Maven with GraalVM Native Image support
- **SSH Client**: JSch (compatible with Native Image)

### Configuration
- Main config: `src/main/resources/application.properties`
- Custom properties under `airopscat.*` namespace
- External configuration support for production deployments
- Environment variable support for sensitive values
- Logging configured for file rotation (10MB max, 30 days retention)

### Native Compilation
- Supports GraalVM Native Image with specific build arguments
- Profile: `native` for native compilation
- Compatible with cross-platform builds (Linux, macOS, Windows)
- Optimized for fast startup (< 0.2s) and low memory usage

### Build Packaging
- Standard build creates executable JAR
- Native build creates single executable file
- Assembly plugin packages distribution
- Separate systemd service setup script available

### Key Dependencies
- MapStruct for object mapping
- Lombok for code generation
- Apache Commons (Lang3, Collections4, Codec)
- JSch for SSH connectivity
- Jackson for JSON processing

## Code Patterns

### Entity Management
- JPA entities in `model/entity/`
- DTOs for data transfer in `model/dto/`
- View objects in `model/vo/`
- Enums in `model/enums/`

### Service Layer
- Business logic encapsulation
- Transaction management with Quarkus
- SSH operations abstracted through service layer
- Strategy pattern for core management and protocol conversion

### Security Integration
- Role-based access control with Quarkus Security
- Form-based authentication configuration
- Encryption utilities for sensitive data
- Custom security policies defined in application.properties

### API Structure
- RESTful endpoints under `/api/` prefix
- Admin operations under `/api/admin/`
- Subscription endpoints for client configuration
- Bark notification endpoints

This project follows Quarkus conventions and uses SQLite for simplicity while maintaining enterprise-grade security and functionality.