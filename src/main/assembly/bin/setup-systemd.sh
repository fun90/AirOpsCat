#!/bin/bash

# 设置AirOpsCat systemd服务
# 在远程服务器上运行此脚本来设置systemd服务

REMOTE_HOST="your_host" # 根据实际情况修改远程服务器地址
REMOTE_USER="root"
REMOTE_DIR="your_remote_dir" # 根据实际情况修改远程部署目录
SSH_KEY_PATH="your_key"  # 根据实际情况修改密钥路径
JAVA_HOME="/root/.sdkman/candidates/java/current" # 根据实际情况修改Java安装路径

echo "=========================================="
echo "        设置 AirOpsCat systemd 服务"
echo "=========================================="

# 远程设置服务
remote_commands="
echo '创建systemd服务文件...'
cat > /etc/systemd/system/airopscat.service << 'EOF'
[Unit]
Description=AirOpsCat Application
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=$REMOTE_DIR
ExecStart=$JAVA_HOME/bin/java -server -jar $REMOTE_DIR/airopscat.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

echo '重新加载systemd配置...'
systemctl daemon-reload
echo '启用服务...'
systemctl enable airopscat
echo '启动服务...'
systemctl start airopscat
echo '检查服务状态...'
systemctl status airopscat --no-pager -l
"

if ssh -i "$SSH_KEY_PATH" "$REMOTE_USER@$REMOTE_HOST" "$remote_commands"; then
    echo "[SUCCESS] systemd服务设置完成"
    echo "[INFO] 服务管理命令："
    echo "  启动: systemctl start airopscat"
    echo "  停止: systemctl stop airopscat"
    echo "  重启: systemctl restart airopscat"
    echo "  状态: systemctl status airopscat"
    echo "  日志: journalctl -u airopscat -f"
else
    echo "[ERROR] systemd服务设置失败"
    exit 1
fi 