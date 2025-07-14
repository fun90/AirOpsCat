#!/bin/bash

# AirOpsCat 部署脚本 (Quarkus 版本)
# 上传 Quarkus 应用并重启服务

# 配置
REMOTE_HOST="your_host" # 根据实际情况修改远程服务器地址
REMOTE_USER="root"
LOCAL_DIR="target"
REMOTE_DIR="/opt/airopscat" # 根据实际情况修改远程部署目录
SSH_KEY_PATH="your_key"  # 根据实际情况修改密钥路径

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
    if [ ! -f "$LOCAL_DIR/$NATIVE_FILE" ]; then
        echo "错误: Native 可执行文件 $LOCAL_DIR/$NATIVE_FILE 不存在"
        echo "当前目录文件列表："
        ls -la $LOCAL_DIR/*-runner 2>/dev/null || echo "  没有找到 Native 可执行文件"
        exit 1
    fi
else
    if [ ! -f "$LOCAL_DIR/quarkus-app/app/$APP_JAR" ]; then
        echo "错误: 应用 JAR 文件 $LOCAL_DIR/quarkus-app/app/$APP_JAR 不存在"
        exit 1
    fi
    if [ ! -f "$LOCAL_DIR/quarkus-app/$QUARKUS_JAR" ]; then
        echo "错误: Quarkus JAR 文件 $LOCAL_DIR/quarkus-app/$QUARKUS_JAR 不存在"
        exit 1
    fi
fi

echo "[INFO] 开始上传文件..."
# 上传文件
if [ "$NATIVE_MODE" = "native" ]; then
    if scp -i "$SSH_KEY_PATH" "$LOCAL_DIR/$NATIVE_FILE" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/"; then
        echo "[SUCCESS] Native 文件上传成功"
    else
        echo "[ERROR] Native 文件上传失败"
        exit 1
    fi
else
    # 创建远程目录结构
    ssh -i "$SSH_KEY_PATH" "$REMOTE_USER@$REMOTE_HOST" "mkdir -p $REMOTE_DIR/quarkus-app/app"
    
    # 上传 Quarkus 应用文件
    if scp -i "$SSH_KEY_PATH" "$LOCAL_DIR/quarkus-app/app/$APP_JAR" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/quarkus-app/app/"; then
        echo "[SUCCESS] 应用 JAR 文件上传成功"
    else
        echo "[ERROR] 应用 JAR 文件上传失败"
        exit 1
    fi
    
    # 上传 Quarkus 运行文件
    if scp -i "$SSH_KEY_PATH" "$LOCAL_DIR/quarkus-app/$QUARKUS_JAR" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/quarkus-app/"; then
        echo "[SUCCESS] Quarkus 运行文件上传成功"
    else
        echo "[ERROR] Quarkus 运行文件上传失败"
        exit 1
    fi
fi

echo "[INFO] 开始远程安装..."
# 根据部署模式设置不同的远程命令
if [ "$NATIVE_MODE" = "native" ]; then
    remote_commands="
    cd $REMOTE_DIR
    echo '部署新版本...'
    mv $NATIVE_FILE airopscat
    chmod +x airopscat
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
    cd $REMOTE_DIR
    echo '部署完成，文件已直接覆盖到 quarkus-app 目录'
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