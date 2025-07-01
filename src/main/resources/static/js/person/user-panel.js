// 用户面板数据管理
const userPanelData = {
    // 数据状态
    accounts: [],
    currentAccount: null,
    selectedAccountId: '',
    selectedPlatform: 'android',
    loading: false,
    
    // 平台配置数据
    platformConfigs: {
        android: {
            title: 'Android 客户端配置',
            defaultClient: 'clash-meta',
            clients: [
                { name: 'clash-meta', title: 'Clash Meta for Android' }
            ],
            steps: [
                '下载并安装 Clash Meta for Android',
                '打开应用，点击"配置"',
                '点击右上角"+"号，选择"新建配置"',
                '选择"从URL导入"，粘贴上述订阅链接',
                '点击"下载"，等待配置加载完成',
                '返回主界面，点击"启动"开始使用'
            ]
        },
        harmony: {
            title: 'HarmonyOS 客户端配置',
            defaultClient: 'clash-meta',
            clients: [
                { name: 'clash-meta', title: 'Clash Meta for HarmonyOS' }
            ],
            steps: [
                '在华为应用市场下载 Clash Meta',
                '打开应用，点击"配置"',
                '点击右上角"+"号，选择"新建配置"',
                '选择"从URL导入"，粘贴上述订阅链接',
                '点击"下载"，等待配置加载完成',
                '返回主界面，点击"启动"开始使用'
            ]
        },
        ios: {
            title: 'iOS 客户端配置',
            defaultClient: 'shadowrocket',
            clients: [
                { name: 'shadowrocket', title: 'Shadowrocket' },
                { name: 'stash', title: 'Stash' },
                { name: 'loon', title: 'Loon' }
            ],
            steps: [
                '在 App Store 下载 Shadowrocket',
                '打开应用，点击右上角"+"号',
                '选择"订阅"，粘贴上述订阅链接',
                '点击"保存"，等待配置加载完成',
                '在主界面点击"连接"开始使用'
            ]
        },
        windows: {
            title: 'Windows 客户端配置',
            defaultClient: 'clash-verge',
            clients: [
                { name: 'clash-verge', title: 'Clash Verge' }
            ],
            steps: [
                '下载并安装 Clash Verge for Windows',
                '打开应用，点击"配置"',
                '点击"新建配置"，选择"从URL导入"',
                '粘贴上述订阅链接，点击"下载"',
                '等待配置加载完成，点击"启动"',
                '在系统托盘右键点击图标，选择"设置为系统代理"'
            ]
        },
        macos: {
            title: 'macOS 客户端配置',
            defaultClient: 'clash-verge',
            clients: [
                { name: 'clash-verge', title: 'Clash Verge' }
            ],
            steps: [
                '下载并安装 Clash Verge for macOS',
                '打开应用，点击"配置"',
                '点击"新建配置"，选择"从URL导入"',
                '粘贴上述订阅链接，点击"下载"',
                '等待配置加载完成，点击"启动"',
                '在菜单栏点击图标，选择"设置为系统代理"'
            ]
        },
        linux: {
            title: 'Linux 客户端配置',
            defaultClient: 'clash-verge',
            clients: [
                { name: 'clash-verge', title: 'Clash Verge' }
            ],
            steps: [
                '下载并安装 Clash Verge for Linux',
                '打开应用，点击"配置"',
                '点击"新建配置"，选择"从URL导入"',
                '粘贴上述订阅链接，点击"下载"',
                '等待配置加载完成，点击"启动"',
                '在系统托盘右键点击图标，选择"设置为系统代理"'
            ]
        }
    },
    
    // 初始化
    mounted() {
        this.loadAccounts();
    },
    
    // 加载用户账户列表
    async loadAccounts() {
        this.loading = true;
        try {
            const response = await fetch('/api/admin/accounts/my-accounts?size=100');
            if (response.ok) {
                const data = await response.json();
                this.accounts = data.records || [];
                
                // 如果有账户，默认选择第一个
                if (this.accounts.length > 0 && !this.selectedAccountId) {
                    this.selectedAccountId = this.accounts[0].id;
                    await this.loadAccountInfo();
                }
            } else {
                console.error('Failed to load accounts');
                this.showToast('加载账户列表失败', 'error');
            }
        } catch (error) {
            console.error('Error loading accounts:', error);
            this.showToast('加载账户列表失败', 'error');
        } finally {
            this.loading = false;
        }
    },
    
    // 刷新账户列表
    async refreshAccounts() {
        await this.loadAccounts();
        this.showToast('账户列表已刷新', 'success');
    },
    
    // 加载指定账户的详细信息
    async loadAccountInfo() {
        if (!this.selectedAccountId) {
            this.currentAccount = null;
            return;
        }
        
        this.loading = true;
        try {
            const response = await fetch(`/api/admin/accounts/${this.selectedAccountId}`);
            if (response.ok) {
                this.currentAccount = await response.json();
            } else {
                console.error('Failed to load account info');
                this.showToast('加载账户信息失败', 'error');
            }
        } catch (error) {
            console.error('Error loading account info:', error);
            this.showToast('加载账户信息失败', 'error');
        } finally {
            this.loading = false;
        }
    },
    
    // 处理账户选择变化
    async onAccountChange() {
        if (this.selectedAccountId) {
            await this.loadAccountInfo();
        } else {
            this.currentAccount = null;
        }
    },
    
    // 获取特定平台的订阅URL
    getSubscriptionUrl(osName, appName) {
        if (!this.currentAccount) return '';
        const baseUrl = window.location.origin;
        return `${baseUrl}/subscribe/config/${this.currentAccount.authCode}/${osName}/${appName}`;
    },
    
    // 获取导入链接
    getImportUrl(platform, client) {
        const subscriptionUrl = this.getSubscriptionUrl(platform, client);
        const encodedUrl = encodeURIComponent(subscriptionUrl);
        
        switch (client) {
            case 'clash-meta':
            case 'clash-verge':
                return `clash://install-config?url=${encodedUrl}`;
            case 'stash':
                return `stash://install-config?url=${encodedUrl}`;
            case 'loon':
                return `loon://import?sub=${encodedUrl}`;
            case 'shadowrocket':
                return `shadowrocket://add/${encodedUrl}`;
            default:
                return '#';
        }
    },
    
    // 获取平台标题
    getPlatformTitle() {
        const config = this.platformConfigs[this.selectedPlatform];
        return config ? config.title : '';
    },
    
    // 获取默认客户端
    getDefaultClient() {
        const config = this.platformConfigs[this.selectedPlatform];
        return config ? config.defaultClient : 'clash-meta';
    },
    
    // 获取配置步骤
    getConfigSteps() {
        const config = this.platformConfigs[this.selectedPlatform];
        return config ? config.steps : [];
    },
    
    // 获取其他客户端
    getOtherClients() {
        const config = this.platformConfigs[this.selectedPlatform];
        if (!config) return [];
        
        return config.clients.filter(client => client.name !== config.defaultClient);
    },
    
    // 复制到剪贴板
    async copyToClipboard(text) {
        try {
            await navigator.clipboard.writeText(text);
            this.showToast('已复制到剪贴板', 'success');
        } catch (error) {
            console.error('Failed to copy to clipboard:', error);
            // 降级方案
            const textArea = document.createElement('textarea');
            textArea.value = text;
            document.body.appendChild(textArea);
            textArea.select();
            document.execCommand('copy');
            document.body.removeChild(textArea);
            this.showToast('已复制到剪贴板', 'success');
        }
    },

    // 导入配置到客户端
    importConfig(platform, client) {
        const subscriptionUrl = this.getSubscriptionUrl(platform, client);
        const encodedUrl = encodeURIComponent(subscriptionUrl);

        let importUrl;
        switch (client) {
            case 'clash-meta':
            case 'clash-verge':
                importUrl = `clash://install-config?url=${encodedUrl}`;
                break;
            case 'stash':
                importUrl = `stash://install-config?url=${encodedUrl}`;
                break;
            case 'loon':
                importUrl = `loon://import?sub=${encodedUrl}`;
                break;
            case 'shadowrocket':
                importUrl = `shadowrocket://add/${encodedUrl}`;
                break;
            default:
                this.showToast('不支持的客户端类型', 'error');
                return;
        }
        console.log(importUrl);
        // 尝试打开客户端应用
        window.open(importUrl, '_blank');

        // 显示提示信息
        setTimeout(() => {
            this.showToast('正在尝试打开客户端应用，如果没有自动打开，请手动复制订阅链接', 'info');
        }, 1000);
    },

    // 格式化日期
    formatDate(dateString) {
        if (!dateString) return '';
        const date = new Date(dateString);
        return date.toLocaleString('zh-CN', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit'
        });
    },
    
    // 格式化字节数
    formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    },
    
    // 获取到期时间样式类
    getExpiryClass() {
        if (!this.currentAccount || !this.currentAccount.daysUntilExpiration) return '';
        
        const days = this.currentAccount.daysUntilExpiration;
        if (days <= 0) return 'text-danger fw-bold';
        if (days <= 7) return 'text-warning fw-bold';
        if (days <= 30) return 'text-info';
        return 'text-success';
    },

    // 计算上传流量百分比
    getUploadPercentage() {
        if (!this.currentAccount || !this.currentAccount.bandwidth || this.currentAccount.bandwidth <= 0) return 0;
        
        const totalBandwidthBytes = this.currentAccount.bandwidth * 1024 * 1024 * 1024;
        return Math.min((this.currentAccount.usedUploadBytes / totalBandwidthBytes) * 100, 100);
    },

    // 计算下载流量百分比
    getDownloadPercentage() {
        if (!this.currentAccount || !this.currentAccount.bandwidth || this.currentAccount.bandwidth <= 0) return 0;
        
        const totalBandwidthBytes = this.currentAccount.bandwidth * 1024 * 1024 * 1024;
        return Math.min((this.currentAccount.usedDownloadBytes / totalBandwidthBytes) * 100, 100);
    },
    
    // 计算剩余流量百分比
    getFreePercentage() {
        if (!this.currentAccount || !this.currentAccount.bandwidth || this.currentAccount.bandwidth <= 0) return 0;
        
        const totalBandwidthBytes = this.currentAccount.bandwidth * 1024 * 1024 * 1024;
        const usedBytes = this.currentAccount.totalUsedBytes;
        const freeBytes = Math.max(0, totalBandwidthBytes - usedBytes);
        return Math.min((freeBytes / totalBandwidthBytes) * 100, 100);
    },
    
    // 计算剩余流量字节数
    getFreeBytes() {
        if (!this.currentAccount || !this.currentAccount.bandwidth || this.currentAccount.bandwidth <= 0) return 0;
        
        const totalBandwidthBytes = this.currentAccount.bandwidth * 1024 * 1024 * 1024;
        const usedBytes = this.currentAccount.totalUsedBytes;
        return Math.max(0, totalBandwidthBytes - usedBytes);
    },
    
    // 获取流量使用状态
    getUsageStatus() {
        if (!this.currentAccount || !this.currentAccount.bandwidth || this.currentAccount.bandwidth <= 0) {
            return { class: 'text-success', icon: 'check' };
        }
        
        const totalBandwidthBytes = this.currentAccount.bandwidth * 1024 * 1024 * 1024;
        const usedBytes = this.currentAccount.totalUsedBytes;
        const percentage = Math.min((usedBytes / totalBandwidthBytes) * 100, 100);
        
        if (percentage >= 90) return { class: 'text-danger', icon: 'warning' };
        if (percentage >= 75) return { class: 'text-warning', icon: 'alert' };
        if (percentage >= 50) return { class: 'text-info', icon: 'info' };
        return { class: 'text-success', icon: 'check' };
    },
    
    // 显示提示消息
    showToast(message, type = 'info') {
        // 使用全局的ToastUtils或简单的alert
        if (typeof ToastUtils !== 'undefined') {
            ToastUtils.show('提示', message, type);
        } else {
            alert(message);
        }
    }
};

// 创建petite-vue应用
PetiteVue.createApp(userPanelData).mount('#userPanel'); 