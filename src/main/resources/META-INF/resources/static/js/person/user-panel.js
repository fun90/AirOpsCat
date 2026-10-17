// 用户面板：仅展示当前登录用户自己的账户，数据来自 /api/me/accounts
import { Modal } from '/static/tabler/js/tabler.esm.min.js';
import { copyToClipboard as copyText, formatDateTimeFull } from '/static/js/common/common.js';

const GB = 1024 * 1024 * 1024;
const DAY_MS = 24 * 60 * 60 * 1000;

const SCHEMES = {
    'clash-meta': (url) => `clash://install-config?url=${url}`,
    'clash-verge': (url) => `clash://install-config?url=${url}`,
    stash: (url) => `stash://install-config?url=${url}`,
    loon: (url) => `loon://import?sub=${url}`,
    shadowrocket: (url) => `shadowrocket://add/${url}`
};

const clashSteps = (install) => [
    install,
    '打开应用，进入"配置"页面',
    '选择"新建配置 / 从 URL 导入"，粘贴上方订阅链接并下载',
    '返回主界面，启动代理即可使用'
];

const platformConfigs = {
    android: {
        name: 'Android',
        defaultClient: 'clash-meta',
        clients: [{ name: 'clash-meta', title: 'Clash Meta' }, { name: 'sing-box', title: 'sing-box' }],
        steps: clashSteps('下载并安装 Clash Meta for Android')
    },
    harmony: {
        name: 'HarmonyOS',
        defaultClient: 'clash-meta',
        clients: [{ name: 'clash-meta', title: 'Clash Meta' }, { name: 'sing-box', title: 'sing-box' }],
        steps: clashSteps('在华为应用市场下载 Clash Meta')
    },
    ios: {
        name: 'iOS',
        defaultClient: 'shadowrocket',
        clients: [
            { name: 'shadowrocket', title: 'Shadowrocket' },
            { name: 'stash', title: 'Stash' },
            { name: 'loon', title: 'Loon' },
            { name: 'sing-box', title: 'sing-box' }
        ],
        steps: [
            '在 App Store 下载所选客户端',
            '打开应用，点击右上角"+"，选择"订阅 / 从 URL 导入"',
            '粘贴上方订阅链接并保存，等待配置加载完成',
            '回到主界面，点击"连接"开始使用'
        ]
    },
    windows: {
        name: 'Windows',
        defaultClient: 'clash-verge',
        clients: [{ name: 'clash-verge', title: 'Clash Verge' }, { name: 'sing-box', title: 'sing-box' }],
        steps: [...clashSteps('下载并安装 Clash Verge for Windows'), '在系统托盘右键图标，选择"系统代理"']
    },
    macos: {
        name: 'macOS',
        defaultClient: 'clash-verge',
        clients: [{ name: 'clash-verge', title: 'Clash Verge' }, { name: 'sing-box', title: 'sing-box' }],
        steps: [...clashSteps('下载并安装 Clash Verge for macOS'), '在菜单栏点击图标，选择"系统代理"']
    },
    linux: {
        name: 'Linux',
        defaultClient: 'clash-verge',
        clients: [{ name: 'clash-verge', title: 'Clash Verge' }, { name: 'sing-box', title: 'sing-box' }],
        steps: clashSteps('下载并安装 Clash Verge for Linux')
    }
};

const toast = (type, message) => (window.ToastUtils ? window.ToastUtils[type](message, '提示') : alert(message));

const userPanel = {
    accounts: [],
    selectedId: null,
    account: null,
    selectedPlatform: 'android',
    selectedClient: 'clash-meta',
    loading: false,
    loaded: false,
    loadError: '',
    resetting: false,
    platformConfigs,
    platformList: Object.entries(platformConfigs).map(([key, config]) => ({ key, name: config.name })),

    mounted() {
        this.selectPlatform(this.detectPlatform());
        this.load();
    },

    // 按浏览器环境预选平台
    detectPlatform() {
        const ua = navigator.userAgent;
        if (/HarmonyOS/i.test(ua)) return 'harmony';
        if (/Android/i.test(ua)) return 'android';
        if (/iPhone|iPad|iPod/i.test(ua)) return 'ios';
        if (/Windows/i.test(ua)) return 'windows';
        if (/Mac/i.test(ua)) return 'macos';
        if (/Linux/i.test(ua)) return 'linux';
        return 'android';
    },

    async load(manual = false) {
        this.loading = true;
        this.loadError = '';
        try {
            const response = await fetch('/api/me/accounts');
            if (response.status === 401) {
                window.location.href = '/login';
                return;
            }
            if (!response.ok) {
                throw new Error(`服务器返回 ${response.status}`);
            }
            this.accounts = await response.json();
            if (!this.accounts.some((item) => item.id === this.selectedId)) {
                this.selectedId = this.accounts[0]?.id ?? null;
            }
            this.syncAccount();
            this.loaded = true;
            if (manual) toast('success', '已刷新');
        } catch (error) {
            console.error('Failed to load accounts:', error);
            this.loadError = error.message || '请稍后重试';
        } finally {
            this.loading = false;
        }
    },

    // petite-vue 不支持 getter 响应式，account 在数据变化后显式同步
    syncAccount() {
        this.account = this.accounts.find((item) => item.id === this.selectedId) ?? null;
    },

    platform() {
        return platformConfigs[this.selectedPlatform];
    },

    client() {
        const clients = this.platform().clients;
        return clients.find((item) => item.name === this.selectedClient) ?? clients[0];
    },

    selectPlatform(key) {
        this.selectedPlatform = key;
        this.selectedClient = platformConfigs[key].defaultClient;
    },

    subscriptionUrl() {
        if (!this.account) return '';
        return `${window.location.origin}/subscribe/config/${this.account.authCode}/${this.selectedPlatform}/${this.client().name}`;
    },

    importUrl() {
        const build = SCHEMES[this.client().name];
        return build ? build(encodeURIComponent(this.subscriptionUrl())) : '';
    },

    importConfig() {
        window.location.href = this.importUrl();
        setTimeout(() => toast('info', '如果客户端没有自动打开，请复制订阅链接手动导入'), 1000);
    },

    async copy(text) {
        try {
            await copyText(text);
            toast('success', '已复制到剪贴板');
        } catch (error) {
            console.error('Failed to copy:', error);
            toast('error', '复制失败，请手动选择复制');
        }
    },

    openResetModal() {
        Modal.getOrCreateInstance(document.getElementById('resetSubscriptionModal')).show();
    },

    async resetSubscription() {
        if (!this.account || this.resetting) return;
        this.resetting = true;
        try {
            const response = await fetch(`/api/me/accounts/${this.account.id}/reset-auth`, { method: 'POST' });
            if (!response.ok) {
                throw new Error((await response.json().catch(() => ({}))).message || `服务器返回 ${response.status}`);
            }
            const updated = await response.json();
            this.accounts = this.accounts.map((item) => (item.id === updated.id ? updated : item));
            this.syncAccount();
            Modal.getOrCreateInstance(document.getElementById('resetSubscriptionModal')).hide();
            toast('success', '订阅链接已重置，请使用新链接重新导入客户端');
        } catch (error) {
            console.error('Failed to reset subscription:', error);
            toast('error', `重置失败：${error.message}`);
        } finally {
            this.resetting = false;
        }
    },

    // 账户状态
    status() {
        const account = this.account;
        if (account.disabled === 1) return { text: '已禁用', color: 'red' };
        if (this.expiry().expired) return { text: '已到期', color: 'red' };
        if (account.trafficOverQuotaLimited) return { text: '流量已用尽', color: 'orange' };
        return { text: '正常', color: 'green' };
    },

    expiry() {
        const toDate = this.account.toDate;
        if (!toDate) return { text: '', class: '', expired: false };
        const days = Math.ceil((new Date(toDate).getTime() - Date.now()) / DAY_MS);
        if (days <= 0) return { text: '已到期', class: 'text-danger', expired: true };
        if (days <= 7) return { text: `剩余 ${days} 天`, class: 'text-warning', expired: false, soon: true };
        return { text: `剩余 ${days} 天`, class: 'text-secondary', expired: false };
    },

    traffic() {
        const account = this.account;
        const used = account.totalUsedBytes || 0;
        const quota = (account.effectiveBandwidth || 0) * GB;
        if (quota <= 0) return { limited: false, used };
        const percent = Math.min((used / quota) * 100, 100);
        return {
            limited: true,
            used,
            quotaText: this.formatBytes(quota),
            free: Math.max(quota - used, 0),
            percent,
            uploadPercent: Math.min(((account.usedUploadBytes || 0) / quota) * 100, 100),
            downloadPercent: Math.min(((account.usedDownloadBytes || 0) / quota) * 100, 100),
            class: percent >= 90 ? 'text-danger' : percent >= 75 ? 'text-warning' : 'text-success'
        };
    },

    alerts() {
        const list = [];
        if (this.account.disabled === 1) {
            list.push({ type: 'danger', icon: 'ti-ban', text: '账户已被禁用，订阅暂不可用，请联系管理员。' });
        }
        if (this.expiry().expired) {
            list.push({ type: 'danger', icon: 'ti-calendar-off', text: '账户已到期，请联系管理员续费。' });
        } else if (this.expiry().soon) {
            list.push({ type: 'warning', icon: 'ti-calendar-time', text: `账户将在 ${this.expiry().text.replace('剩余 ', '')}后到期，请及时续费。` });
        }
        if (this.account.trafficOverQuotaLimited) {
            list.push({ type: 'danger', icon: 'ti-chart-pie-off', text: '本周期流量已用尽，下个统计周期自动恢复。' });
        } else if (this.traffic().limited && this.traffic().percent >= 90) {
            list.push({ type: 'warning', icon: 'ti-alert-triangle', text: '本周期流量即将用尽。' });
        }
        return list;
    },

    speedText() {
        const { downloadMbps, uploadMbps } = this.account;
        const parts = [];
        if (downloadMbps > 0) parts.push(`下行 ${downloadMbps} Mbps`);
        if (uploadMbps > 0) parts.push(`上行 ${uploadMbps} Mbps`);
        return parts.join(' / ') || '不限速';
    },

    formatDate(value) {
        return formatDateTimeFull(value);
    },

    formatDay(value) {
        return formatDateTimeFull(value).slice(0, 10);
    },

    formatBytes(bytes) {
        if (!bytes || bytes < 0) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
        return `${parseFloat((bytes / Math.pow(1024, i)).toFixed(2))} ${units[i]}`;
    }
};

PetiteVue.createApp(userPanel).mount('#app');
