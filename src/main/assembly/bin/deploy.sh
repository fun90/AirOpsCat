#!/bin/bash

# AirOpsCat 部署脚本 (Quarkus 版本)
# 上传 Quarkus 应用并重启服务

# 配置
REMOTE_HOST="${AIROPSCAT_REMOTE_HOST:-your_host}" # 从环境变量读取或使用默认值
REMOTE_USER="${AIROPSCAT_REMOTE_USER:-root}"
LOCAL_DIR="${AIROPSCAT_LOCAL_DIR:-target}"
REMOTE_DIR="${AIROPSCAT_REMOTE_DIR:-/opt/airopscat}" # 从环境变量读取或使用默认值
SSH_KEY_PATH="${AIROPSCAT_SSH_KEY_PATH:-your_key}"  # 从环境变量读取或使用默认值

# 临时文件配置
TEMP_DIR="/tmp/airopscat-deploy-$$"
COMPRESS_FILE="airopscat-deploy.tar.gz"

# 清理函数
cleanup() {
    echo "[INFO] 清理临时文件..."
    rm -rf "$TEMP_DIR"
}

# 设置退出时自动清理
trap cleanup EXIT

# 检查参数
if [ $# -eq 0 ]; then
    echo "用法: $0 <版本号> [native]"
    echo "示例: $0 1.0.2        # 部署 JVM 版本"
    echo "示例: $0 1.0.2 native # 部署 Native 版本"
    exit 1
fi

VERSION=$1
NATIVE_MODE=$2

# 根据部署模式选择文件
if [ "$NATIVE_MODE" = "native" ]; then
    NATIVE_FILE="airopscat-${VERSION}-runner"
    echo "部署模式: Native 可执行文件"
else
    APP_JAR="airopscat-${VERSION}.jar"
    QUARKUS_JAR="quarkus-run.jar"
    echo "部署模式: JVM JAR 文件"
fi

echo "=========================================="
echo "        AirOpsCat 部署脚本 (Quarkus)"
echo "=========================================="
echo "版本号: $VERSION"
if [ "$NATIVE_MODE" = "native" ]; then
    echo "应用文件: $NATIVE_FILE"
else
    echo "应用文件: $APP_JAR, $QUARKUS_JAR"
fi
echo "远程服务器: $REMOTE_HOST"
echo "部署目录: $REMOTE_DIR"
echo ""

# 检查文件是否存在
if [ "$NATIVE_MODE" = "native" ]; then
    echo "编译native文件..."
    ./mvnw clean package -Pnative -DskipTests -Dquarkus.native.additional-build-args=-J-Xmx8g
    if [ ! -f "$LOCAL_DIR/$NATIVE_FILE" ]; then
        echo "错误: Native 可执行文件 $LOCAL_DIR/$NATIVE_FILE 不存在"
        echo "当前目录文件列表："
        ls -la $LOCAL_DIR/*-runner 2>/dev/null || echo "  没有找到 Native 可执行文件"
        exit 1
    fi
else
    echo "打包应用..."
    ./mvnw clean package
    if [ ! -f "$LOCAL_DIR/quarkus-app/app/$APP_JAR" ]; then
        echo "错误: 应用 JAR 文件 $LOCAL_DIR/quarkus-app/app/$APP_JAR 不存在"
        exit 1
    fi
    if [ ! -f "$LOCAL_DIR/quarkus-app/$QUARKUS_JAR" ]; then
        echo "错误: Quarkus JAR 文件 $LOCAL_DIR/quarkus-app/$QUARKUS_JAR 不存在"
        exit 1
    fi
fi

# 创建临时目录用于打包
echo "[INFO] 创建临时目录进行文件打包..."
mkdir -p "$TEMP_DIR"

# 根据部署模式准备文件
if [ "$NATIVE_MODE" = "native" ]; then
    echo "[INFO] 准备 Native 文件..."
    cp "$LOCAL_DIR/$NATIVE_FILE" "$TEMP_DIR/"
    cd "$TEMP_DIR"
    
    # 显示原始文件大小
    ORIGINAL_SIZE=$(du -h "$NATIVE_FILE" | cut -f1)
    echo "[INFO] 原始文件大小: $ORIGINAL_SIZE"
    
    # 压缩文件
    echo "[INFO] 压缩文件中..."
    tar -czf "$COMPRESS_FILE" "$NATIVE_FILE"
    
    # 显示压缩后大小和压缩比
    COMPRESSED_SIZE=$(du -h "$COMPRESS_FILE" | cut -f1)
    COMPRESSION_RATIO=$(echo "scale=1; $(stat -c%s "$COMPRESS_FILE") * 100 / $(stat -c%s "$NATIVE_FILE")" | bc -l)
    echo "[INFO] 压缩后大小: $COMPRESSED_SIZE (压缩率: ${COMPRESSION_RATIO}%)"
else
    echo "[INFO] 准备 JVM 文件..."
    # 复制整个 quarkus-app 目录结构
    cp -r "$LOCAL_DIR/quarkus-app" "$TEMP_DIR/"
    cd "$TEMP_DIR"
    
    # 显示原始文件大小
    ORIGINAL_SIZE=$(du -sh quarkus-app | cut -f1)
    echo "[INFO] 原始文件大小: $ORIGINAL_SIZE"
    
    # 压缩文件
    echo "[INFO] 压缩文件中..."
    tar -czf "$COMPRESS_FILE" quarkus-app/
    
    # 显示压缩后大小和压缩比
    COMPRESSED_SIZE=$(du -h "$COMPRESS_FILE" | cut -f1)
    ORIGINAL_BYTES=$(du -sb quarkus-app | cut -f1)
    COMPRESSED_BYTES=$(stat -c%s "$COMPRESS_FILE")
    COMPRESSION_RATIO=$(echo "scale=1; $COMPRESSED_BYTES * 100 / $ORIGINAL_BYTES" | bc -l)
    echo "[INFO] 压缩后大小: $COMPRESSED_SIZE (压缩率: ${COMPRESSION_RATIO}%)"
fi

echo "[INFO] 开始上传压缩文件..."
# 上传压缩文件
if scp -i "$SSH_KEY_PATH" "$COMPRESS_FILE" "$REMOTE_USER@$REMOTE_HOST:/tmp/"; then
    echo "[SUCCESS] 压缩文件上传成功"
else
    echo "[ERROR] 压缩文件上传失败"
    exit 1
fi

echo "[INFO] 开始远程解压和安装..."
# 根据部署模式设置不同的远程命令
if [ "$NATIVE_MODE" = "native" ]; then
    remote_commands="
    cd /tmp
    echo '解压文件...'
    tar -xzf $COMPRESS_FILE
    echo '备份当前版本...'
    if [ -f $REMOTE_DIR/airopscat ]; then
        cp $REMOTE_DIR/airopscat $REMOTE_DIR/airopscat.backup.\$(date +%Y%m%d_%H%M%S)
    fi
    echo '部署新版本...'
    mkdir -p $REMOTE_DIR
    mv $NATIVE_FILE $REMOTE_DIR/airopscat
    chmod +x $REMOTE_DIR/airopscat
    echo '清理临时文件...'
    rm -f /tmp/$COMPRESS_FILE
    echo '重启systemd服务...'
    systemctl restart airopscat
    echo '等待服务启动...'
    sleep 3
    echo '检查服务状态...'
    if systemctl is-active --quiet airopscat; then
        echo '服务启动成功'
        systemctl status airopscat --no-pager -l
    else
        echo '服务启动失败'
        systemctl status airopscat --no-pager -l
        exit 1
    fi
    "
else
    remote_commands="
    cd /tmp
    echo '解压文件...'
    tar -xzf $COMPRESS_FILE
    echo '备份当前版本...'
    if [ -d $REMOTE_DIR/quarkus-app ]; then
        mv $REMOTE_DIR/quarkus-app $REMOTE_DIR/quarkus-app.backup.\$(date +%Y%m%d_%H%M%S)
    fi
    echo '部署新版本...'
    mkdir -p $REMOTE_DIR
    mv quarkus-app $REMOTE_DIR/
    echo '清理临时文件...'
    rm -f /tmp/$COMPRESS_FILE
    echo '重启systemd服务...'
    systemctl restart airopscat
    echo '等待服务启动...'
    sleep 5
    echo '检查服务状态...'
    if systemctl is-active --quiet airopscat; then
        echo '服务启动成功'
        systemctl status airopscat --no-pager -l
    else
        echo '服务启动失败'
        systemctl status airopscat --no-pager -l
        exit 1
    fi
    "
fi

if ssh -i "$SSH_KEY_PATH" "$REMOTE_USER@$REMOTE_HOST" "$remote_commands"; then
    echo "[SUCCESS] 部署完成"
    echo "[INFO] 验证服务状态..."
    # 再次验证服务状态
    if ssh -i "$SSH_KEY_PATH" "$REMOTE_USER@$REMOTE_HOST" "systemctl is-active airopscat"; then
        echo "[SUCCESS] 服务运行正常"
        echo "[INFO] 查看服务日志: journalctl -u airopscat -f"
    else
        echo "[WARNING] 服务可能未正常运行，请手动检查"
    fi
else
    echo "[ERROR] 部署失败"
    exit 1
fi 