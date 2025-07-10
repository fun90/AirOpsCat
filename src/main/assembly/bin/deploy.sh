#!/bin/bash

# AirOpsCat 部署脚本 (使用systemd)
# 上传jar文件并重启服务

# 配置
REMOTE_HOST="your_host" # 根据实际情况修改远程服务器地址
REMOTE_USER="root"
LOCAL_DIR="your_project_path/target" # 根据实际情况修改本地jar文件路径
REMOTE_DIR="your_remote_dir" # 根据实际情况修改远程部署目录
SSH_KEY_PATH="your_key"  # 根据实际情况修改密钥路径

# 检查参数
if [ $# -eq 0 ]; then
    echo "用法: $0 <版本号>"
    echo "示例: $0 1.0.2"
    exit 1
fi

VERSION=$1
JAR_FILE="airopscat-${VERSION}.jar"

echo "=========================================="
echo "        AirOpsCat 部署脚本 (systemd)"
echo "=========================================="
echo "版本号: $VERSION"
echo "JAR文件: $JAR_FILE"
echo "远程服务器: $REMOTE_HOST"
echo "部署目录: $REMOTE_DIR"
echo ""

# 检查文件是否存在
if [ ! -f "$LOCAL_DIR/$JAR_FILE" ]; then
    echo "错误: JAR文件 $LOCAL_DIR/$JAR_FILE 不存在"
    echo "当前目录文件列表："
    ls -la $LOCAL_DIR/*.jar 2>/dev/null || echo "  没有找到JAR文件"
    exit 1
fi

echo "[INFO] 开始上传文件..."
# 上传文件
if scp -i "$SSH_KEY_PATH" "$LOCAL_DIR/$JAR_FILE" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/"; then
    echo "[SUCCESS] 文件上传成功"
else
    echo "[ERROR] 文件上传失败"
    exit 1
fi

echo "[INFO] 开始远程安装..."
# 远程执行安装命令
remote_commands="
cd $REMOTE_DIR
echo '重命名新版本...'
mv $JAR_FILE airopscat.jar
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