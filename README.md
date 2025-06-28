# AirOpsCat
一个轻量、灵活、高效的服务器管理系统，保持敏捷，掌控一切。🐱💨

## 目标
* 简单、小巧
* 有设计、扩展性强

## 快速开始

### 下载预构建版本
从 [GitHub Releases](../../releases) 下载对应平台的 native 可执行文件：
- **Linux**: `airopscat-linux-amd64.tar.gz`
- **macOS**: `airopscat-macos-amd64.tar.gz`
- **Windows**: `airopscat-windows-amd64.exe.zip`

解压后直接运行，无需安装 Java 环境。

### 本地构建

#### 标准 JAR 构建
```bash
./mvnw clean package
java -jar target/airopscat-1.0.0.jar
```

#### Native 可执行文件构建
需要安装 [GraalVM 21](https://www.graalvm.org/downloads/) 和 Native Image：

```bash
# 构建 native 可执行文件
./mvnw -Pnative native:compile

# 运行
./target/airopscat
```

详细的 native 构建说明请参考 [Native Build Guide](docs/native-build-guide.md)。

## 自动化构建

项目使用 GitHub Actions 自动构建跨平台的 native 可执行文件：
- 每次推送到 `main` 或 `develop` 分支时触发构建
- 创建 `v*` 标签时自动发布到 GitHub Releases
- 支持 Linux、macOS、Windows 三个平台

## 设计图

## 功能模块


用户账户
- 用户管理
- 账户管理
- 账户流量

基础设备
- 域名
- 服务器

代理设置
- 配置模板
- 节点管理

财务
- 账单流水

