# AirOpsCat Native Build Guide

本文档说明如何使用 GitHub Actions 自动构建跨平台的 native 可执行文件。

## 构建触发条件

GitHub workflow 会在以下情况下自动触发：

1. **推送到主分支**: 推送到 `main` 或 `develop` 分支
2. **创建标签**: 创建以 `v` 开头的标签（如 `v1.0.0`）
3. **Pull Request**: 针对 `main` 分支的 PR
4. **手动触发**: 在 GitHub Actions 页面手动运行

## 构建平台

workflow 会同时在三个平台上构建：

- **Linux** (ubuntu-latest) - 生成 `airopscat-linux-amd64`
- **macOS** (macos-latest) - 生成 `airopscat-macos-amd64`  
- **Windows** (windows-latest) - 生成 `airopscat-windows-amd64.exe`

## 构建过程

每个平台的构建过程包括：

1. **环境准备**
   - 检出代码
   - 安装 GraalVM 21
   - 缓存 Maven 依赖

2. **编译和测试**
   - 编译 Java 代码
   - 运行单元测试

3. **Native Image 构建**
   - 使用 GraalVM Native Image 生成原生可执行文件
   - 应用优化参数

4. **打包分发**
   - 重命名可执行文件
   - 创建分发包（tar.gz 或 zip）
   - 上传构建产物

## 本地构建

如果你想在本地构建 native 可执行文件：

### 前提条件

1. 安装 GraalVM 21:
   ```bash
   # 使用 SDKMAN
   sdk install java 21.0.1-graal
   sdk use java 21.0.1-graal
   
   # 或者下载并安装 GraalVM
   # https://www.graalvm.org/downloads/
   ```

2. 安装 Native Image:
   ```bash
   gu install native-image
   ```

### 构建命令

```bash
# 清理并编译
./mvnw clean compile

# 运行测试
./mvnw test

# 构建 native 可执行文件
./mvnw -Pnative native:compile
```

构建完成后，可执行文件位于 `target/airopscat`（Linux/macOS）或 `target/airopscat.exe`（Windows）。

## 配置说明

### GraalVM Native Image 配置

项目包含以下 Native Image 配置：

- **资源配置**: `src/main/resources/META-INF/native-image/resource-config.json`
- **反射配置**: `src/main/resources/META-INF/native-image/reflect-config.json`
- **JNI 配置**: `src/main/resources/META-INF/native-image/jni-config.json`

### 构建参数

在 `pom.xml` 中配置的主要构建参数：

```xml
<buildArgs>
    <buildArg>--initialize-at-build-time=org.apache.sshd</buildArg>
    <buildArg>--enable-all-security-services</buildArg>
    <buildArg>--allow-incomplete-classpath</buildArg>
    <buildArg>-H:+ReportExceptionStackTraces</buildArg>
    <buildArg>-H:+AddAllCharsets</buildArg>
    <buildArg>-H:IncludeResourceBundles=javax.servlet.LocalStrings</buildArg>
    <buildArg>--enable-https</buildArg>
    <buildArg>--enable-http</buildArg>
</buildArgs>
```

## 发布流程

当你创建一个以 `v` 开头的 Git 标签时，workflow 会自动：

1. 构建所有平台的可执行文件
2. 创建 GitHub Release
3. 上传所有构建产物到 Release

### 创建发布

```bash
# 创建并推送标签
git tag v1.0.0
git push origin v1.0.0
```

## 下载使用

用户可以从 GitHub Releases 页面下载对应平台的可执行文件：

- **Linux**: 下载 `airopscat-linux-amd64.tar.gz`
- **macOS**: 下载 `airopscat-macos-amd64.tar.gz`
- **Windows**: 下载 `airopscat-windows-amd64.exe.zip`

解压后直接运行可执行文件，无需安装 Java 运行时。

## 故障排除

### 常见问题

1. **构建失败**: 检查 GitHub Actions 日志中的错误信息
2. **内存不足**: Native Image 构建需要较多内存，GitHub Actions 提供的资源通常足够
3. **依赖问题**: 确保所有依赖都支持 Native Image

### 调试技巧

1. 在本地使用相同的构建命令进行测试
2. 检查 Native Image 的详细日志
3. 使用 `--verbose` 参数获取更多构建信息

## 优化建议

1. **构建时间**: 使用 Maven 依赖缓存减少构建时间
2. **文件大小**: 通过 UPX 等工具进一步压缩可执行文件
3. **启动速度**: 调整 Native Image 参数优化启动性能

## 注意事项

- Native 可执行文件只能在对应的操作系统和架构上运行
- 某些 Java 特性在 Native Image 中可能不被支持
- 首次构建可能需要较长时间，后续构建会利用缓存加速 