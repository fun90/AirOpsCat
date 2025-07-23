#!/bin/bash

# 设置AirOpsCat systemd服务 (Quarkus 版本)
# 支持 JVM 和 Native 两种运行模式

# 默认配置
REMOTE_HOST="${AIROPSCAT_REMOTE_HOST:-your_host}" # 从环境变量读取或使用默认值
REMOTE_USER="${AIROPSCAT_REMOTE_USER:-root}"
REMOTE_DIR="${AIROPSCAT_REMOTE_DIR:-/opt/airopscat}" # 从环境变量读取或使用默认值
SSH_KEY_PATH="${AIROPSCAT_SSH_KEY_PATH:-your_key}"  # 从环境变量读取或使用默认值
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}" # 从环境变量读取或使用默认值

# 检查参数
if [ $# -eq 0 ]; then
    echo "用法: $0 <运行模式> [选项]"
    echo "运行模式:"
    echo "  jvm     - 使用 JVM 模式运行 Quarkus 应用"
    echo "  native  - 使用 Native 模式运行 Quarkus 应用"
    echo ""
    echo "选项:"
    echo "  --host <主机>     - 远程服务器地址"
    echo "  --user <用户>     - 远程用户名"
    echo "  --dir <目录>      - 远程部署目录"
    echo "  --key <密钥路径>  - SSH 密钥路径"
    echo "  --java <Java路径> - Java 安装路径 (仅 JVM 模式)"
    echo ""
    echo "示例:"
    echo "  $0 jvm --host myserver.com --dir /opt/airopscat"
    echo "  $0 native --host myserver.com --dir /opt/airopscat"
    exit 1
fi

RUN_MODE=$1
shift

# 解析选项
while [[ $# -gt 0 ]]; do
    case $1 in
        --host)
            REMOTE_HOST="$2"
            shift 2
            ;;
        --user)
            REMOTE_USER="$2"
            shift 2
            ;;
        --dir)
            REMOTE_DIR="$2"
            shift 2
            ;;
        --key)
            SSH_KEY_PATH="$2"
            shift 2
            ;;
        --java)
            JAVA_HOME="$2"
            shift 2
            ;;
        *)
            echo "未知选项: $1"
            exit 1
            ;;
    esac
done

# 验证运行模式
if [ "$RUN_MODE" != "jvm" ] && [ "$RUN_MODE" != "native" ]; then
    echo "错误: 运行模式必须是 'jvm' 或 'native'"
    exit 1
fi

echo "=========================================="
echo "        设置 AirOpsCat systemd 服务"
echo "=========================================="
echo "运行模式: $RUN_MODE"
echo "远程服务器: $REMOTE_HOST"
echo "部署目录: $REMOTE_DIR"
echo "用户: $REMOTE_USER"
if [ "$RUN_MODE" = "jvm" ]; then
    echo "Java 路径: $JAVA_HOME"
fi
echo ""

# 根据运行模式创建不同的 systemd 配置
if [ "$RUN_MODE" = "jvm" ]; then
    remote_commands="
    echo '创建 JVM 模式 systemd 服务文件...'
    cat > /etc/systemd/system/airopscat.service << 'EOF'
[Unit]
Description=AirOpsCat Application (Quarkus JVM)
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=$REMOTE_DIR
ExecStart=$JAVA_HOME/bin/java -server -Xmx256m -Xms256m -Dquarkus.config.locations=$REMOTE_DIR/quarkus-app/application.properties -jar $REMOTE_DIR/quarkus-app/quarkus-run.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
Environment=JAVA_HOME=$JAVA_HOME

# Quarkus JVM 模式优化
Environment=QUARKUS_PROFILE=prod
Environment=QUARKUS_LOG_LEVEL=INFO

[Install]
WantedBy=multi-user.target
EOF

    echo '重新加载systemd配置...'
    systemctl daemon-reload
    echo '启用服务...'
    systemctl enable airopscat
    echo '启动服务...'
    systemctl start airopscat
    echo '等待服务启动...'
    sleep 5
    echo '检查服务状态...'
    systemctl status airopscat --no-pager -l
    "
else
    remote_commands="
    echo '创建 Native 模式 systemd 服务文件...'
    cat > /etc/systemd/system/airopscat.service << 'EOF'
[Unit]
Description=AirOpsCat Application (Quarkus Native)
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=$REMOTE_DIR
ExecStart=$REMOTE_DIR/airopscat -Dquarkus.config.locations=$REMOTE_DIR/application.properties -Duser.timezone=Asia/Shanghai
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

# Quarkus Native 模式优化
Environment=QUARKUS_PROFILE=prod
Environment=QUARKUS_LOG_LEVEL=INFO

# Native 模式资源限制
LimitNOFILE=65536
LimitNPROC=4096

[Install]
WantedBy=multi-user.target
EOF

    echo '设置可执行权限...'
    chmod +x $REMOTE_DIR/airopscat
    echo '重新加载systemd配置...'
    systemctl daemon-reload
    echo '启用服务...'
    systemctl enable airopscat
    echo '启动服务...'
    systemctl start airopscat
    echo '等待服务启动...'
    sleep 3
    echo '检查服务状态...'
    systemctl status airopscat --no-pager -l
    "
fi

# 验证必要参数
if [ "$SSH_KEY_PATH" = "your_key" ] || [ "$REMOTE_HOST" = "your_host" ]; then
    echo "[ERROR] 请先配置必要的参数:"
    echo "  SSH_KEY_PATH: $SSH_KEY_PATH"
    echo "  REMOTE_HOST: $REMOTE_HOST"
    echo ""
    echo "请使用命令行参数或修改脚本中的默认值"
    exit 1
fi

# 检查 SSH 密钥文件是否存在
if [ ! -f "$SSH_KEY_PATH" ]; then
    echo "[ERROR] SSH 密钥文件不存在: $SSH_KEY_PATH"
    exit 1
fi

# 测试 SSH 连接
#echo "[INFO] 测试 SSH 连接..."
#if ! ssh -i "$SSH_KEY_PATH" -o ConnectTimeout=10 "$REMOTE_USER@$REMOTE_HOST" "echo 'SSH 连接成功'" 2>/dev/null; then
#    echo "[ERROR] SSH 连接失败，请检查:"
#    echo "  - 主机地址: $REMOTE_HOST"
#    echo "  - 用户名: $REMOTE_USER"
#    echo "  - SSH 密钥: $SSH_KEY_PATH"
#    echo "  - 网络连接"
#    exit 1
#fi

# 执行远程命令
echo "[INFO] 开始设置 systemd 服务..."
if ssh -i "$SSH_KEY_PATH" "$REMOTE_USER@$REMOTE_HOST" "$remote_commands"; then
    echo "[SUCCESS] systemd 服务设置完成"
    echo ""
    echo "=========================================="
    echo "              服务管理命令"
    echo "=========================================="
    echo "  启动: systemctl start airopscat"
    echo "  停止: systemctl stop airopscat"
    echo "  重启: systemctl restart airopscat"
    echo "  状态: systemctl status airopscat"
    echo "  日志: journalctl -u airopscat -f"
    echo ""
    echo "=========================================="
    echo "              配置信息"
    echo "=========================================="
    echo "  运行模式: $RUN_MODE"
    echo "  服务文件: /etc/systemd/system/airopscat.service"
    echo "  工作目录: $REMOTE_DIR"
    if [ "$RUN_MODE" = "jvm" ]; then
        echo "  Java 路径: $JAVA_HOME"
        echo "  内存设置: -Xmx512m -Xms256m"
        echo "  执行文件: $REMOTE_DIR/quarkus-app/quarkus-run.jar"
    else
        echo "  执行文件: $REMOTE_DIR/airopscat"
        echo "  启动时间: < 0.2 秒"
    fi
    echo ""
    echo "[INFO] 可以通过以下命令验证服务状态:"
    echo "  ssh -i $SSH_KEY_PATH $REMOTE_USER@$REMOTE_HOST 'systemctl status airopscat'"
else
    echo "[ERROR] systemd 服务设置失败"
    echo "[INFO] 请检查远程服务器的以下项目:"
    echo "  - systemd 是否可用"
    echo "  - 用户是否有 sudo 权限"
    echo "  - 目录 $REMOTE_DIR 是否存在"
    if [ "$RUN_MODE" = "jvm" ]; then
        echo "  - Java 是否安装在 $JAVA_HOME"
        echo "  - JAR 文件是否存在于 $REMOTE_DIR/quarkus-app/quarkus-run.jar"
    else
        echo "  - Native 可执行文件是否存在于 $REMOTE_DIR/airopscat"
    fi
    exit 1
fi 