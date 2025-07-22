# AirOpsCat 🐱💨

一个轻量、灵活、高效的服务器管理系统，保持敏捷，掌控一切。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/projects/jdk/21/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.24.3-brightgreen.svg)](https://quarkus.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build Status](https://img.shields.io/badge/Build-Passing-success.svg)](https://github.com/fun90/AirOpsCat)

## 📖 项目简介

AirOpsCat 是一个基于 Quarkus 3.24.3 构建的现代化服务器管理系统，专为代理服务提供商设计。通过 GraalVM Native Image 技术实现超快启动时间和低内存占用，提供完整的用户管理、服务器管理、节点配置、流量统计和订阅服务等功能。

预览
![PC端](./docs/pc-preview-1.png)

### 🎯 设计理念
- **简单小巧**: 轻量级架构，快速部署
- **扩展性强**: 模块化设计，易于定制
- **高效稳定**: 基于 Quarkus 3.24.3 和 Java 21
- **用户友好**: 现代化 Web 界面，响应式设计
- **云原生**: 支持 Native Image，超快启动时间（< 0.2秒）

## ✨ 功能列表

| 功能模块 | 主要特性 | 说明 |
|---------|---------|------|
| 👥 **用户管理** | 多角色权限控制、用户认证、面板自助服务 | 支持管理员、合作伙伴、VIP用户三种角色 |
| 💰 **账户管理** | 生命周期管理、流量统计、在线IP监控 | 流量配额管理、认证码生成、使用统计 |
| 🖥️ **服务器管理** | SSH连接、配置模板、自动化部署 | 服务器信息管理和远程操作 |
| 🌐 **节点管理** | 多协议支持、部署配置、状态监控 | 支持VLESS、Shadowsocks、SOCKS协议 |
| 📱 **订阅服务** | 多平台支持、动态配置、实时更新 | 适配各主流客户端的订阅生成 |
| 📊 **数据统计** | 流量统计、用户监控、财务记录 | 详细的使用数据分析和报表 |
| 🔔 **通知服务** | Bark推送、系统告警、用户通知 | 实时消息推送和状态提醒 |

## 🛠️ 技术栈

### 后端技术
- **框架**: Quarkus 3.24.3
- **语言**: Java 21
- **数据库**: SQLite + JPA/Hibernate
- **安全**: Quarkus Security + JPA Security
- **模板引擎**: Qute
- **构建工具**: Maven + GraalVM Native Image

### 前端技术
- **UI框架**: Tabler UI 1.3.2
- **响应式**: petite-vue 0.4.1
- **样式**: Bootstrap 5 + CSS3
- **图标**: Tabler Icons

## 🚀 快速开始

### 环境要求
- **运行环境**: 
  - 方式一：Java 21 或更高版本（JAR 运行）
  - 方式二：无需 Java 环境（Native Image 运行）
- **构建环境**（仅开发时需要）:
  - GraalVM 21.0.6+ （Native Image 构建）
  - Maven 3.6+ 或使用项目内置的 Maven Wrapper

### 方式一：下载预构建版本（推荐）

从 [GitHub Releases](https://github.com/fun90/AirOpsCat/releases) 下载对应平台的 native 可执行文件：

```bash
# 下载并运行 Linux 版本
wget https://github.com/fun90/AirOpsCat/releases/latest/download/airopscat-linux-amd64
chmod +x airopscat-linux-amd64

# 直接运行（使用内置配置）
./airopscat-linux-amd64

# 使用外部配置文件运行（推荐）
./airopscat-linux-amd64 -Dquarkus.config.locations=./application.properties
```

**Native Image 优势**:
- **极快启动**: < 0.2 秒启动时间
- **低内存占用**: 相比 JVM 减少 70%+ 内存使用
- **无需 Java 环境**: 可独立运行
- **文件大小**: 约 128MB 单文件可执行程序

### 方式二：开发模式运行

```bash
# 克隆项目
git clone https://github.com/fun90/AirOpsCat.git
cd AirOpsCat

# 开发模式运行（支持热重载）
./mvnw quarkus:dev

# 访问开发控制台
# http://localhost:8080/q/dev
```

### 方式三：标准 JAR 构建

```bash
# 构建项目
./mvnw clean package

# 运行应用
java -jar target/quarkus-app/quarkus-run.jar

# 使用外部配置文件运行
java -Dquarkus.config.locations=./application.properties -jar target/quarkus-app/quarkus-run.jar
```

### 方式四：Native Image 构建

需要安装 [GraalVM 21](https://www.graalvm.org/downloads/) 和 Native Image：

```bash
# 安装 GraalVM Native Image 组件
gu install native-image

# 构建 native 可执行文件（需要 8GB+ 内存，约 4 分钟）
./mvnw package -Pnative -DskipTests -Dquarkus.native.additional-build-args=-J-Xmx8g

# 运行（< 0.2s 启动时间）
./target/airopscat-linux-amd64
```

## ⚙️ 配置说明

### 基础配置

应用支持内置配置和外部配置文件两种方式。外部配置文件优先级更高，推荐在生产环境使用。

外部 `application.properties` 文件（通过 `-Dquarkus.config.locations` 指定）

## 🚀 部署指南

### Native Image 部署（推荐）

```bash
# 下载并部署 Native Image
wget https://github.com/fun90/AirOpsCat/releases/latest/download/airopscat-linux-amd64
chmod +x airopscat-linux-amd64

# 创建外部配置文件
cat > application.properties << EOF
quarkus.http.port=8080
airopscat.crypto.secret-key=your-production-secret-key
airopscat.subscription.url=https://your-domain.com/subscribe
airopscat.bark.url=https://api.day.app
airopscat.bark.device-key=your-device-key
EOF

# 启动应用
./airopscat-linux-amd64 -Dquarkus.config.locations=./application.properties
```

### 系统服务部署

#### Systemd 服务

```bash
# 创建应用目录
sudo mkdir -p /opt/airopscat
sudo cp airopscat-linux-amd64 /opt/airopscat/
sudo cp application.properties /opt/airopscat/
sudo chmod +x /opt/airopscat/airopscat-linux-amd64

# 创建 systemd 服务文件
sudo tee /etc/systemd/system/airopscat.service > /dev/null << EOF
[Unit]
Description=AirOpsCat Server Management System
After=network.target

[Service]
Type=exec
User=airopscat
Group=airopscat
WorkingDirectory=/opt/airopscat
ExecStart=/opt/airopscat/airopscat-linux-amd64 -Dquarkus.config.locations=/opt/airopscat/application.properties
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

# 创建用户和启动服务
sudo useradd -r -s /bin/false airopscat
sudo chown -R airopscat:airopscat /opt/airopscat
sudo systemctl daemon-reload
sudo systemctl enable airopscat
sudo systemctl start airopscat
```

### 反向代理配置

#### Nginx 配置示例

```nginx
server {
    listen 80;
    server_name your-domain.com;
    
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## 🤝 贡献指南

我们欢迎所有形式的贡献！

### 开发环境搭建

1. Fork 项目
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

### 代码规范

- 遵循 Java 编码规范
- 使用 Lombok 简化代码
- 添加适当的注释
- 编写单元测试

### 提交规范

```
feat: 新功能
fix: 修复bug
docs: 文档更新
style: 代码格式调整
refactor: 代码重构
test: 测试相关
chore: 构建过程或辅助工具的变动
```

## 📝 更新日志

### v2.0.1 (最新) - Quarkus 版本
- **重大更新**: 从 Spring Boot 迁移到 Quarkus 3.24.3
- **Native Image 支持**: 超快启动时间（< 0.2秒）和低内存占用
- **开发体验提升**: 支持热重载开发模式 (`./mvnw quarkus:dev`)
- **外部配置支持**: 支持外部 application.properties 文件覆盖默认配置
- **JSON 反序列化优化**: 修复 Native Image 中的 JSON 处理问题
- **模板引擎升级**: 从 Thymeleaf 迁移到 Qute
- 新增用户面板功能
- 优化在线IP统计
- 增强Bark通知服务
- 改进订阅模板系统

### v1.0.1
- 添加多协议支持
- 优化SSH连接管理
- 改进安全配置

### v1.0.0
- 初始版本发布
- 基础功能实现

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 🙏 致谢

- [Quarkus](https://quarkus.io/) - 云原生 Java 框架
- [GraalVM](https://www.graalvm.org/) - Native Image 支持
- [Tabler UI](https://tabler.io/) - 现代化的UI组件库
- [petite-vue](https://github.com/vuejs/petite-vue) - 轻量级Vue.js
- [Qute](https://quarkus.io/guides/qute) - 现代化模板引擎
- [Hibernate ORM](https://hibernate.org/orm/) - 对象关系映射框架

## 📞 联系我们

- **项目地址**: [https://github.com/fun90/AirOpsCat](https://github.com/fun90/AirOpsCat)
- **问题反馈**: [Issues](https://github.com/fun90/AirOpsCat/issues)
- **讨论交流**: [Discussions](https://github.com/fun90/AirOpsCat/discussions)

---

⭐ 如果这个项目对您有帮助，请给我们一个星标！