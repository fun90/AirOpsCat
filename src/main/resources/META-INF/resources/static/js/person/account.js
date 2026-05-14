import { DataTable } from '/static/js/common/data-table.js';
import { formatDateTimeForLocal, formatRelativeTime, formatDateTimeFull } from '/static/js/common/common.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';
import { createRemoteSearchConfig, createUserSearch } from '/static/js/common/tom-select-helper.js';

const DEFAULT_ACCOUNT_FILTERS = Object.freeze({
    userId: '',
    status: '',
    onlineStatus: ''
});

const accountTable = new DataTable({
    data: {
        entityName: 'accounts',
        modalIdPrefix: 'account-',
        filters: { ...DEFAULT_ACCOUNT_FILTERS },
        periodTypes: [],
        paymentMethods: [],
        availableTags: [],
        users: [],

        // 搜索组件实例
        userSearch: null,
        editUserSearch: null,
        stats: {
            total: 0,
            active: 0,
            expired: 0,
            disabled: 0,
            expiringSoon: 0,
            onlineUsers: 0
        },
        configUrl: '',
        configUrlModal: null,
        configOptions: {
            osName: '',
            appName: ''
        },
        // 操作系统和应用的关联关系
        osAppMapping: {
            'windows': ['clash-verge', 'sing-box'],
            'macos': ['clash-verge', 'sing-box'],
            'linux': ['clash-verge', 'sing-box'],
            'android': ['clash-meta', 'sing-box'],
            'harmony': ['clash-meta', 'sing-box'],
            'ios': ['shadowrocket', 'loon', 'stash', 'clash-mi', 'sing-box']
        },
        // 所有可用的应用列表
        allApps: [
            { value: 'clash-verge', label: 'Clash Verge' },
            { value: 'clash-meta', label: 'Clash Meta' },
            { value: 'stash', label: 'Stash' },
            { value: 'sing-box', label: 'sing-box' },
            { value: 'shadowrocket', label: 'Shadowrocket' },
            { value: 'loon', label: 'Loon' },
            { value: 'clash-mi', label: 'Clash Mi' },
            { value: 'v2rayng', label: 'V2rayNG' }
        ],
        availableApps: [],
        renewModal: null,
        renewData: {
            expiryDate: '',
            amount: '',
            paymentMethod: ''
        },
        resetAuthCodeModal: null,
        // 在线连接相关数据
        onlineConnectionsModal: null,
        onlineConnections: [],
        onlineConnectionsLoading: false,
        // 账号详情相关数据
        accountDetailsModal: null,
        subscriptionDomainBindings: [],
        subscriptionDomainBindingsLoading: false,
        subscriptionDomainBindingModal: null,
        subscriptionDomainBindingNodeSearch: null,
        subscriptionDomainBindingDomainSearch: null,
        subscriptionDomainBindingForm: {
            id: null,
            accountId: null,
            nodeId: '',
            nodeName: '',
            nodeServerHost: '',
            nodePort: null,
            domainDnsRecordId: '',
            fullName: '',
            enabled: 1,
            remark: ''
        },
        // 文档链接配置
        docsBaseUrl: '', // 将从后端获取
        newItem: {
            userId: '',
            level: 0,
            nodePrefix: '8',
            fromDate: '',
            toDate: '',
            periodType: 'MONTHLY',
            uuid: '',
            authCode: '',
            accountNo: '',
            maxConnections: 0,
            speed: 0,
            bandwidth: 0,
            disabled: false,
            remark: '',
            tagIds: []
        },
        currentItem: {},
        deployModal: null,
    },
    methods: {
        formatDateTimeFull(dateTime) {
            return formatDateTimeFull(dateTime);
        },

        // Initialize any additional data
        initialize() {
            this.fetchPeriodTypes();
            this.fetchPaymentMethods();
            this.fetchAvailableTags();
            this.fetchDocsConfig();
            this.fetchUsers();
            this.initializeSearchComponents();
        },

        // Initialize search dropdown components
        initializeSearchComponents() {
            document.getElementById('account-createModal').addEventListener('show.bs.modal', () => {
                if (this.userSearch) {
                    this.userSearch.destroy();
                }
                this.userSearch = new TomSelect(document.getElementById('user-search'),
                    createUserSearch((value) => { this.newItem.userId = value || ''; }, this.users)
                );
            });

            document.getElementById('account-editModal').addEventListener('show.bs.modal', () => {
                if (this.editUserSearch) {
                    this.editUserSearch.destroy();
                }
                this.editUserSearch = new TomSelect(document.getElementById('edit-user-search'),
                    createUserSearch((value) => { this.editedItem.userId = value || ''; }, this.users)
                );
                if (this.editedItem && this.editedItem.userId) {
                    this.editUserSearch.addOption({ id: this.editedItem.userId, nickName: this.editedItem.nickName || '' });
                    this.editUserSearch.setValue(this.editedItem.userId, true);
                }
            });

            document.getElementById('subscriptionDomainBindingModal').addEventListener('show.bs.modal', () => {
                this.initializeSubscriptionDomainBindingSearches();
            });
        },

        fetchPeriodTypes() {
            fetch('/api/admin/accounts/period-types')
                .then(response => response.json())
                .then(data => {
                    this.periodTypes = data;
                })
                .catch(error => {
                    console.error('Error fetching period types:', error);
                });
        },

        fetchPaymentMethods() {
            fetch('/api/admin/transactions/paymentMethods')
                .then(response => response.json())
                .then(data => {
                    this.paymentMethods = data;
                })
                .catch(error => {
                    console.error('Error fetching payment methods:', error);
                });
        },

        fetchAvailableTags() {
            fetch('/api/admin/tags/enabled')
                .then(response => response.json())
                .then(data => {
                    this.availableTags = data;
                })
                .catch(error => {
                    console.error('Error fetching available tags:', error);
                });
        },

        fetchUsers() {
            fetch('/api/admin/users?size=10')
                .then(response => response.json())
                .then(data => {
                    this.users = data.records || data;
                })
                .catch(error => {
                    console.error('Error fetching users:', error);
                });
        },

        fetchDocsConfig() {
            fetch('/api/admin/config/docs')
                .then(response => response.json())
                .then(data => {
                    this.docsBaseUrl = data.url || 'https://docs.com';
                })
                .catch(error => {
                    console.error('Error fetching docs config:', error);
                    // 使用默认值
                    this.docsBaseUrl = 'https://docs.com';
                });
        },

        // 生成文档链接
        getDocumentUrl(authCode) {
            if (!authCode || !this.docsBaseUrl) {
                return '';
            }
            return `${this.docsBaseUrl}/?code=${authCode}`;
        },

        getManualUrl(authCode) {
            if (!authCode || !this.docsBaseUrl) {
                return '';
            }
            return `${this.docsBaseUrl}/manual.html?code=${authCode}`;
        },

        getStatusDescription(item) {
            if (item.disabled != null && item.disabled === 1) {
                return "已禁用";
            }

            if (item.toDate == null) {
                return "永不过期";
            }

            const now = new Date();
            const toDate = new Date(item.toDate);

            // 计算天数差
            const timeDiff = toDate.getTime() - now.getTime();
            const days = Math.floor(timeDiff / (1000 * 60 * 60 * 24));

            if (days < 0) {
                return "已过期 " + Math.abs(days) + " 天";
            } else if (days === 0) {
                // 计算小时差
                const hours = Math.floor(timeDiff / (1000 * 60 * 60));
                if (hours <= 0) {
                    // 计算分钟差
                    const minutes = Math.floor(timeDiff / (1000 * 60));
                    return "即将过期 " + minutes + " 分钟";
                }
                return "今天过期 " + hours + " 小时后";
            } else if (days <= 7) {
                return days + " 天后过期";
            } else {
                return "正常 (还有 " + days + " 天)";
            }
        },

        getStatusTyp(item) {
            if (item.disabled != null && item.disabled === 1) {
                return 'disabled';
            }

            if (item.toDate == null) {
                return "active";
            }

            const now = new Date();
            const toDate = new Date(item.toDate);

            // 计算天数差
            const timeDiff = toDate.getTime() - now.getTime();
            const days = Math.floor(timeDiff / (1000 * 60 * 60 * 24));

            if (days < 0) {
                return 'expired';
            }
            return "active";
        },

        // Get Badge classes
        getStatusBadgeClass(item) {
            const statusType = this.getStatusTyp(item);
            switch (statusType) {
                case 'active': return 'bg-success-lt';
                case 'expired': return 'bg-danger-lt';
                case 'disabled': return 'bg-secondary-lt';
                default: return 'bg-secondary-lt';
            }
        },

        getStatusAlertClass(item) {
            const statusType = this.getStatusTyp(item);
            switch (statusType) {
                case 'active': return 'alert-success';
                case 'expired': return 'alert-danger';
                case 'disabled': return 'alert-secondary';
                default: return 'alert-secondary';
            }
        },

        getProgressBarClass(percentage) {
            if (percentage >= 90) return 'bg-danger';
            if (percentage >= 70) return 'bg-warning';
            return 'bg-success';
        },

        formatSpeedLimit(item) {
            if (!item) return '无限制';
            const speed = item.effectiveSpeed != null ? item.effectiveSpeed : item.speed;
            return speed && Number(speed) > 0 ? `${speed} KB/s` : '无限制';
        },

        // 统计周期相关方法
        getPeriodTypeLabel(periodType) {
            if (!periodType) return '未设置';
            const periodTypeObj = this.periodTypes.find(type => type.value === periodType);
            return periodTypeObj ? periodTypeObj.label : periodType;
        },

        getPeriodTypeBadgeClass(periodType) {
            if (!periodType) return 'text-bg-secondary';
            switch (periodType) {
                case 'MONTHLY': return 'text-bg-blue';
                case 'YEARLY': return 'text-bg-green';
                default: return 'text-bg-secondary';
            }
        },

        getCreateTimeDisplay(createTime) {
            if (!createTime) return '创建时间未知';
            return `创建于 ${formatRelativeTime(createTime)}`;
        },

        // 通过状态筛选账户
        filterByStatus(status) {
            this.filters.status = status;
            this.filters.onlineStatus = '';
            this.currentPage = 1;
            this.fetchRecords();
        },

        filterByOnlineStatus(onlineStatus) {
            this.filters.onlineStatus = onlineStatus;
            this.filters.status = '';
            this.currentPage = 1;
            this.fetchRecords();
        },

        clearFilters() {
            this.resetFilters();
        },

        // 查看账号详情
        viewAccountDetails(account) {
            this.selectedItem = account;
            this.fetchSubscriptionDomainBindings(account.id);
            this.accountDetailsModal = new Modal(document.getElementById('accountDetailsModal'));
            this.accountDetailsModal.show();
        },

        initializeSubscriptionDomainBindingSearches() {
            if (this.subscriptionDomainBindingNodeSearch) {
                this.subscriptionDomainBindingNodeSearch.destroy();
            }
            if (this.subscriptionDomainBindingDomainSearch) {
                this.subscriptionDomainBindingDomainSearch.destroy();
            }

            const availableNodeApiUrl = `/api/admin/account-node-subscription-domain-bindings/accounts/${this.subscriptionDomainBindingForm.accountId}/available-nodes`;
            this.subscriptionDomainBindingNodeSearch = new TomSelect(
                document.getElementById('subscription-domain-node-search'),
                createRemoteSearchConfig({
                    apiUrl: availableNodeApiUrl,
                    valueField: 'id',
                    labelField: 'displayName',
                    searchField: ['name', 'serverHost', 'serverIp'],
                    placeholder: '搜索节点...',
                    pageSize: 20,
                    dataTransform: (records) => records.map(node => ({
                        ...node,
                        displayName: this.formatBindingNode(node)
                    })),
                    onChange: (value) => {
                        this.subscriptionDomainBindingForm.nodeId = value || '';
                    }
                })
            );
            this.loadDefaultSubscriptionDomainNodes(availableNodeApiUrl);

            this.subscriptionDomainBindingDomainSearch = new TomSelect(
                document.getElementById('subscription-domain-domain-search'),
                createRemoteSearchConfig({
                    apiUrl: '/api/admin/domain-dns-records',
                    valueField: 'id',
                    labelField: 'fullName',
                    searchField: ['fullName'],
                    placeholder: '搜索DNS记录完整域名...',
                    minQueryLength: 1,
                    render: {
                        option: (data, escape) => `<div>
                            <div>${escape(data.fullName || '')}</div>
                            <div class="text-muted small">${escape(data.type || '')}${data.domain ? ` · ${escape(data.domain)}` : ''}</div>
                        </div>`,
                        item: (data, escape) => `<div>${escape(data.fullName || '')}</div>`
                    },
                    onChange: (value) => {
                        this.subscriptionDomainBindingForm.domainDnsRecordId = value || '';
                    }
                })
            );

            const form = this.subscriptionDomainBindingForm;
            if (form.nodeId) {
                this.subscriptionDomainBindingNodeSearch.addOption({
                    id: form.nodeId,
                    displayName: this.formatBindingNode(form)
                });
                this.subscriptionDomainBindingNodeSearch.setValue(form.nodeId, true);
            }
            if (form.domainDnsRecordId) {
                this.subscriptionDomainBindingDomainSearch.addOption({
                    id: form.domainDnsRecordId,
                    fullName: form.fullName || '',
                    type: form.recordType || '',
                    domain: form.domain || ''
                });
                this.subscriptionDomainBindingDomainSearch.setValue(form.domainDnsRecordId, true);
            }
        },

        loadDefaultSubscriptionDomainNodes(apiUrl) {
            fetch(`${apiUrl}?size=20`)
                .then(response => response.ok ? response.json() : { records: [] })
                .then(data => {
                    if (!this.subscriptionDomainBindingNodeSearch) {
                        return;
                    }
                    const records = (data.records || data || []).map(node => ({
                        ...node,
                        displayName: this.formatBindingNode(node)
                    }));
                    this.subscriptionDomainBindingNodeSearch.addOptions(records);
                    this.subscriptionDomainBindingNodeSearch.refreshOptions(false);
                })
                .catch(error => {
                    console.error('Error loading available nodes:', error);
                });
        },

        fetchSubscriptionDomainBindings(accountId) {
            if (!accountId) {
                this.subscriptionDomainBindings = [];
                return;
            }
            this.subscriptionDomainBindingsLoading = true;
            fetch(`/api/admin/account-node-subscription-domain-bindings/accounts/${accountId}`)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('获取订阅域名绑定失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.subscriptionDomainBindings = data || [];
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '获取订阅域名绑定失败', 'danger');
                })
                .finally(() => {
                    this.subscriptionDomainBindingsLoading = false;
                });
        },

        openSubscriptionDomainBindingModal(binding) {
            if (!this.selectedItem || !this.selectedItem.id) {
                return;
            }
            this.subscriptionDomainBindingForm = binding ? {
                id: binding.id,
                accountId: binding.accountId,
                nodeId: binding.nodeId || '',
                nodeName: binding.nodeName || '',
                nodeServerHost: binding.nodeServerHost || '',
                nodePort: binding.nodePort || null,
                domainDnsRecordId: binding.domainDnsRecordId || '',
                domainId: binding.domainId || '',
                fullName: binding.fullName || '',
                domain: binding.domain || '',
                recordType: binding.recordType || '',
                enabled: binding.enabled == null ? 1 : binding.enabled,
                remark: binding.remark || ''
            } : {
                id: null,
                accountId: this.selectedItem.id,
                nodeId: '',
                nodeName: '',
                nodeServerHost: '',
                nodePort: null,
                domainDnsRecordId: '',
                fullName: '',
                enabled: 1,
                remark: ''
            };
            this.validationErrors = {};
            this.subscriptionDomainBindingModal = new Modal(document.getElementById('subscriptionDomainBindingModal'));
            this.subscriptionDomainBindingModal.show();
        },

        validateSubscriptionDomainBindingForm() {
            this.validationErrors = {};
            if (!this.subscriptionDomainBindingForm.nodeId) {
                this.validationErrors.subscriptionDomainNodeId = '请选择节点';
            }
            if (!this.subscriptionDomainBindingForm.domainDnsRecordId) {
                this.validationErrors.subscriptionDomainDomainId = '请选择DNS记录';
            }
            return Object.keys(this.validationErrors).length === 0;
        },

        saveSubscriptionDomainBinding() {
            if (!this.validateSubscriptionDomainBindingForm() || !this.selectedItem) {
                return;
            }

            fetch('/api/admin/account-node-subscription-domain-bindings', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    accountId: this.selectedItem.id,
                    nodeId: Number(this.subscriptionDomainBindingForm.nodeId),
                    domainDnsRecordId: Number(this.subscriptionDomainBindingForm.domainDnsRecordId),
                    enabled: Number(this.subscriptionDomainBindingForm.enabled || 0),
                    remark: this.subscriptionDomainBindingForm.remark || null
                })
            })
                .then(response => {
                    if (!response.ok) {
                        return response.json().then(data => {
                            throw new Error(data.message || '保存订阅域名绑定失败');
                        });
                    }
                    return response.json();
                })
                .then(() => {
                    this.subscriptionDomainBindingModal.hide();
                    this.fetchSubscriptionDomainBindings(this.selectedItem.id);
                    ToastUtils.show('Success', '订阅域名绑定已保存', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '保存订阅域名绑定失败', 'danger');
                });
        },

        toggleSubscriptionDomainBinding(binding) {
            const action = binding.enabled === 1 ? 'disable' : 'enable';
            fetch(`/api/admin/account-node-subscription-domain-bindings/${binding.id}/${action}`, {
                method: 'PATCH'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('更新订阅域名绑定状态失败');
                    }
                    return response.json();
                })
                .then(() => {
                    this.fetchSubscriptionDomainBindings(this.selectedItem.id);
                    ToastUtils.show('Success', '订阅域名绑定状态已更新', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '更新订阅域名绑定状态失败', 'danger');
                });
        },

        deleteSubscriptionDomainBinding(binding) {
            if (!binding || !binding.id) {
                return;
            }
            fetch(`/api/admin/account-node-subscription-domain-bindings/${binding.id}`, {
                method: 'DELETE'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('删除订阅域名绑定失败');
                    }
                })
                .then(() => {
                    this.fetchSubscriptionDomainBindings(this.selectedItem.id);
                    ToastUtils.show('Success', '订阅域名绑定已删除', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '删除订阅域名绑定失败', 'danger');
                });
        },

        formatBindingNode(node) {
            if (!node) {
                return '-';
            }
            const name = node.nodeName || node.name || `节点#${node.nodeId || node.id}`;
            const host = node.nodeServerHost || node.serverHost || node.serverIp || '-';
            const port = node.nodePort || node.port;
            return `${name} (${host}${port ? ':' + port : ''})`;
        },

        getBindingEnabledBadgeClass(binding) {
            return binding && binding.enabled === 1 ? 'text-bg-success' : 'text-bg-secondary';
        },

        // UUID and Auth code management
        generateUuid() {
            this.newItem.uuid = this.uuidv4();
        },

        generateAuthCode() {
            this.newItem.authCode = this.uuidv4().replace(/-/g, '');
        },

        generateAccountNo() {
            this.newItem.accountNo = this.generateRandomString(12);
        },

        regenerateAuthCode() {
            if (!this.editedItem.id) return;

            // 显示确认弹窗
            this.resetAuthCodeModal = new Modal(document.getElementById('resetAuthCodeModal'));
            this.resetAuthCodeModal.show();
        },

        regenerateUuid() {
            if (!this.editedItem.id) return;

            // 直接重新生成UUID
            this.editedItem.uuid = this.uuidv4();
        },

        resetAuthCode(account) {
            if (!account.id) return;

            // 设置选中的账户并显示确认弹窗
            this.selectedItem = account;
            this.resetAuthCodeModal = new Modal(document.getElementById('resetAuthCodeModal'));
            this.resetAuthCodeModal.show();
        },

        confirmResetAuthCode() {
            // 确定要重置的账户ID
            const accountId = this.selectedItem ? this.selectedItem.id : this.editedItem.id;
            if (!accountId) return;

            fetch(`/api/admin/accounts/${accountId}/reset-auth`, {
                method: 'PATCH'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('重置认证码失败');
                    }
                    return response.json();
                })
                .then(data => {
                    // 关闭确认弹窗
                    if (this.resetAuthCodeModal) {
                        this.resetAuthCodeModal.hide();
                    }

                    // 更新认证码
                    if (this.selectedItem) {
                        // 从表格操作调用的重置
                        const index = this.records.findIndex(a => a.id === accountId);
                        if (index !== -1) {
                            this.records[index].authCode = data.authCode;
                        }
                    } else if (this.editedItem) {
                        // 从编辑弹窗调用的重置
                        this.editedItem.authCode = data.authCode;
                    }

                    ToastUtils.show('Success', '重置认证码成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '重置认证码失败', 'danger');
                });
        },

        // Config URL modal methods
        openConfigUrlModal(account) {
            this.selectedItem = account;
            // Reset config options
            this.configOptions = {
                osName: '',
                appName: ''
            };
            this.configUrl = '';

            this.configUrlModal = new Modal(document.getElementById('configUrlModal'));
            this.configUrlModal.show();
        },

        updateConfigUrl() {
            // Only fetch config URL if both osName and appName are selected
            if (!this.configOptions.osName || !this.configOptions.appName || !this.selectedItem) {
                this.configUrl = '';
                return;
            }

            const params = new URLSearchParams({
                osName: this.configOptions.osName,
                appName: this.configOptions.appName
            });

            fetch(`/api/admin/accounts/${this.selectedItem.id}/config-url?${params}`)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('获取配置链接失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.configUrl = data.configUrl;
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '获取配置链接失败', 'danger');
                    this.configUrl = '';
                });
        },

        // 当操作系统改变时的处理
        onOsNameChange() {
            // 清空应用选择
            this.configOptions.appName = '';
            // 清空配置链接
            this.configUrl = '';

            if (!this.configOptions.osName) {
                this.availableApps = [];
            } else {
                const allowedApps = this.osAppMapping[this.configOptions.osName] || [];
                console.log('availableApps ，allowedApps： {}', allowedApps);
                this.availableApps = this.allApps.filter(app => allowedApps.includes(app.value));
            }
        },

        // Renew account methods
        openRenewAccountModal(account) {
            this.selectedItem = account;

            // Calculate default renewal time (from current time or expiry time +1 month)
            let baseDate;
            if (account.toDate && new Date(account.toDate) > new Date()) {
                baseDate = new Date(account.toDate);
            } else {
                baseDate = new Date();
            }

            // Add one month
            baseDate.setMonth(baseDate.getMonth() + 1);
            this.renewData = {
                expiryDate: formatDateTimeForLocal(baseDate),
                amount: '',
                paymentMethod: ''
            };

            this.validationErrors = {};
            this.renewModal = new Modal(document.getElementById('renewAccountModal'));
            this.renewModal.show();
        },

        setRenewPeriod(value, unit) {
            // Calculate new renewal time (from current time or expiry time)
            let baseDate;
            if (this.selectedItem.toDate && new Date(this.selectedItem.toDate) > new Date()) {
                baseDate = new Date(this.selectedItem.toDate);
            } else {
                baseDate = new Date();
            }

            // Add time based on unit
            if (unit === 'days') {
                baseDate.setDate(baseDate.getDate() + value);
            } else if (unit === 'months') {
                baseDate.setMonth(baseDate.getMonth() + value);
            } else if (unit === 'years') {
                baseDate.setFullYear(baseDate.getFullYear() + value);
            }

            this.renewData.expiryDate = formatDateTimeForLocal(baseDate);
        },

        validateRenewForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.renewData.expiryDate) {
                this.validationErrors.expiryDate = '请选择到期时间';
                isValid = false;
            } else {
                const expiryDate = new Date(this.renewData.expiryDate);
                const now = new Date();
                if (expiryDate <= now) {
                    this.validationErrors.expiryDate = '到期时间必须大于当前时间';
                    isValid = false;
                }
            }

            if (this.renewData.amount !== null && this.renewData.amount !== undefined && String(this.renewData.amount).trim() !== '') {
                const amount = parseFloat(this.renewData.amount);
                if (Number.isNaN(amount) || amount <= 0) {
                    this.validationErrors.amount = '请输入有效金额';
                    isValid = false;
                }
                if (!this.renewData.paymentMethod) {
                    this.validationErrors.paymentMethod = '请选择付款方式';
                    isValid = false;
                }
            }

            return isValid;
        },

        renewAccount() {
            if (!this.validateRenewForm()) {
                return;
            }

            const params = new URLSearchParams({
                expiryDate: this.renewData.expiryDate
            });
            if (this.renewData.amount !== null && this.renewData.amount !== undefined && String(this.renewData.amount).trim() !== '') {
                params.append('amount', this.renewData.amount);
                params.append('paymentMethod', this.renewData.paymentMethod);
            }

            fetch(`/api/admin/accounts/${this.selectedItem.id}/renew?${params.toString()}`, {
                method: 'PATCH'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('续期失败');
                    }
                    return response.json();
                })
                .then(data => {
                    // Update the account in the local records array
                    const index = this.records.findIndex(a => a.id === this.selectedItem.id);
                    if (index !== -1) {
                        this.records[index] = data;
                    }

                    this.renewModal.hide();
                    ToastUtils.show('Success', '续期成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '续期失败', 'danger');
                });
        },

        // Form validation and preparation
        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            // UserID validation
            if (!this.newItem.userId) {
                this.validationErrors.userId = '请选择用户';
                isValid = false;
            }

            // PeriodType validation
            if (!this.newItem.periodType) {
                this.validationErrors.periodType = '请选择统计周期类型';
                isValid = false;
            }

            // NodeMultiple validation
            if (!this.newItem.nodeMultiple) {
                this.validationErrors.nodeMultiple = '请填写倍数';
                isValid = false;
            }

            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            // UserID validation
            if (!this.editedItem.userId) {
                this.validationErrors.userId = '请选择用户';
                isValid = false;
            }

            // PeriodType validation
            if (!this.editedItem.periodType) {
                this.validationErrors.periodType = '请选择统计周期类型';
                isValid = false;
            }

            const uuid = (this.editedItem.uuid || '').trim();
            const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
            if (!uuid) {
                this.validationErrors.uuid = 'UUID不能为空';
                isValid = false;
            } else if (!uuidPattern.test(uuid)) {
                this.validationErrors.uuid = 'UUID格式不合法';
                isValid = false;
            }

            // NodeMultiple validation
            if (!this.editedItem.nodeMultiple) {
                this.validationErrors.nodeMultiple = '请填写倍数';
                isValid = false;
            }

            return isValid;
        },

        prepareCreateData() {
            // Get current time
            const now = new Date();
            const localDateTimeFormat = formatDateTimeForLocal(now);

            // Get one month later for default expiration
            const oneMonthLater = new Date();
            oneMonthLater.setMonth(oneMonthLater.getMonth() + 1);
            const oneMonthLaterFormat = formatDateTimeForLocal(oneMonthLater);

            return {
                userId: this.newItem.userId,
                accountNo: this.newItem.accountNo,
                level: this.newItem.level || null,
                nodeMultiple: this.newItem.nodeMultiple || null,
                nodePrefix: this.newItem.nodePrefix || null,
                fromDate: this.newItem.fromDate || localDateTimeFormat,
                toDate: this.newItem.toDate || oneMonthLaterFormat,
                periodType: this.newItem.periodType,
                uuid: this.newItem.uuid || null, // Will be generated on server if null
                authCode: this.newItem.authCode || null, // Will be generated on server if null
                maxConnections: this.newItem.maxConnections || null,
                speed: this.newItem.speed || null,
                bandwidth: this.newItem.bandwidth || null,
                disabled: this.newItem.disabled ? 1 : 0,
                remark: this.newItem.remark || null,
                tagIds: this.newItem.tagIds || []
            };
        },

        prepareUpdateData() {
            return {
                userId: this.editedItem.userId,
                accountNo: this.editedItem.accountNo,
                level: this.editedItem.level,
                nodeMultiple: this.editedItem.nodeMultiple || null,
                nodePrefix: this.editedItem.nodePrefix || '',
                fromDate: this.editedItem.fromDate || null,
                toDate: this.editedItem.toDate || null,
                periodType: this.editedItem.periodType,
                maxConnections: this.editedItem.maxConnections,
                speed: this.editedItem.speed,
                bandwidth: this.editedItem.bandwidth,
                disabled: this.editedItem.disabled,
                remark: this.editedItem.remark || null,
                tagIds: this.editedItem.tagIds || [],
                uuid: this.editedItem.uuid ? this.editedItem.uuid.trim() : ''
            };
        },

        resetCreateForm() {
            // Get current time
            const now = new Date();
            const localDateTimeFormat = formatDateTimeForLocal(now);

            // Get one month later for default expiration
            const oneMonthLater = new Date();
            oneMonthLater.setMonth(oneMonthLater.getMonth() + 1);
            const oneMonthLaterFormat = formatDateTimeForLocal(oneMonthLater);

            this.newItem = {
                userId: '',
                accountNo: '',
                level: 0,
                nodePrefix: '8',
                fromDate: localDateTimeFormat,
                toDate: oneMonthLaterFormat,
                periodType: 'MONTHLY',
                uuid: '',
                authCode: '',
                maxConnections: 0,
                speed: 2048,
                bandwidth: 0,
                disabled: false,
                remark: '',
                tagIds: []
            };

            // Reset search components
            if (this.userSearch) {
                this.userSearch.clear();
            }
        },

        prepareEditForm(account) {
            // Format dates for datetime-local input
            const formatDateForInput = (dateString) => {
                if (!dateString) return '';
                const date = new Date(dateString);
                return formatDateTimeForLocal(date);
            };

            // Load current account tags
            this.loadAccountTags(account.id);

            return {
                id: account.id,
                userId: account.userId,
                nickName: account.nickName,
                accountNo: account.accountNo,
                level: account.level,
                nodeMultiple: account.nodeMultiple,
                nodePrefix: account.nodePrefix || '',
                fromDate: formatDateForInput(account.fromDate),
                toDate: formatDateForInput(account.toDate),
                periodType: account.periodType,
                uuid: account.uuid,
                authCode: account.authCode,
                maxConnections: account.maxConnections,
                speed: account.speed,
                bandwidth: account.bandwidth,
                disabled: account.disabled,
                remark: account.remark || '',
                tagIds: [] // Will be loaded asynchronously
            };
        },

        afterUpdate(data) {
            if (this.selectedItem && this.selectedItem.id === data.id) {
                this.selectedItem = data;
                this.fetchSubscriptionDomainBindings(data.id);
            }
        },

        loadAccountTags(accountId) {
            fetch(`/api/admin/tags/accounts/${accountId}`)
                .then(response => response.json())
                .then(data => {
                    this.editedItem.tagIds = data.map(tag => tag.id);
                })
                .catch(error => {
                    console.error('Error loading account tags:', error);
                    this.editedItem.tagIds = [];
                });
        },

        // URLs for API calls
        getApiUrl() {
            return '/api/admin/accounts';
        },

        getStatsUrl() {
            return '/api/admin/accounts/stats';
        },

        getToggleStatusUrl(item, action) {
            return `/api/admin/accounts/${item.id}/${action}`;
        },

        // 在线连接相关方法
        viewOnlineConnections(account) {
            this.selectedItem = account;
            this.onlineConnectionsModal = new Modal(document.getElementById('onlineConnectionsModal'));
            this.onlineConnectionsModal.show();
            this.fetchOnlineConnections(account.accountNo);
        },

        fetchOnlineConnections(accountNo) {
            this.onlineConnectionsLoading = true;
            this.onlineConnections = [];

            fetch(`/api/admin/accounts/online/accountNo/${encodeURIComponent(accountNo)}`)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('获取在线连接记录失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.onlineConnections = data;
                })
                .catch(error => {
                    console.error('Error fetching online connections:', error);
                    ToastUtils.show('Error', '获取在线连接记录失败', 'danger');
                })
                .finally(() => {
                    this.onlineConnectionsLoading = false;
                });
        },

        refreshOnlineConnections() {
            if (this.selectedItem) {
                this.fetchOnlineConnections(this.selectedItem.accountNo);
            }
        },

        getOnlineDuration(sessionStartTime) {
            if (!sessionStartTime) return '-';

            const now = new Date();
            const startTime = new Date(sessionStartTime);
            const diffMs = Math.max(0, now.getTime() - startTime.getTime());

            const minutes = Math.floor(diffMs / (1000 * 60));
            const hours = Math.floor(minutes / 60);
            const days = Math.floor(hours / 24);

            if (days > 0) {
                return `${days}天${hours % 24}小时`;
            } else if (hours > 0) {
                return `${hours}小时${minutes % 60}分钟`;
            } else if (minutes > 0) {
                return `${minutes}分钟`;
            } else {
                return '刚刚';
            }
        },

        // 部署到节点
        confirmDeploy(account) {
            this.currentItem = account;
            this.deployModal = new Modal(document.getElementById('account-deployModal'));
            this.deployModal.show();
        },
        deployToNodes() {
            if (!this.currentItem.id) return;

            // 显示加载状态
            ToastUtils.show('Success', '正在部署到节点...', 'info');

            fetch(`/api/admin/accounts/${this.currentItem.id}/deploy`, {
                method: 'PATCH'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('部署失败');
                    }
                    return response.json();
                })
                .then(results => {

                    // 统计部署结果
                    const successCount = results.filter(r => r.success).length;
                    const failCount = results.filter(r => !r.success).length;

                    if (failCount === 0) {
                        ToastUtils.show('Success', `部署成功！成功部署到 ${successCount} 个节点`, 'success');
                        // Hide modal
                        this.deployModal.hide();
                    } else if (successCount === 0) {
                        ToastUtils.show('Error', `部署失败！${failCount} 个节点部署失败`, 'danger');
                    } else {
                        ToastUtils.show('Warning', `部分成功！${successCount} 个节点成功，${failCount} 个节点失败`, 'warning');
                    }

                    // 如果有失败的，显示详细错误信息
                    if (failCount > 0) {
                        console.log('部署失败详情:', results.filter(r => !r.success));
                    }
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '部署到节点失败', 'danger');
                });
        },

        ...createResponsiveFilterMethods({
            createDefaultFilters: () => ({ ...DEFAULT_ACCOUNT_FILTERS }),
            getActiveTags() {
                const tags = this.searchQuery ? [{
                    key: 'search',
                    label: '搜索',
                    value: this.searchQuery
                }] : [];

                if (this.filters.status) {
                    const statusMap = {
                        active: '活跃',
                        expired: '已过期',
                        expiring: '将过期',
                        disabled: '已禁用'
                    };
                    tags.push({ key: 'status', label: '状态', value: statusMap[this.filters.status] || this.filters.status });
                }

                if (this.filters.onlineStatus) {
                    const onlineStatusMap = {
                        online: '在线',
                        offline: '离线'
                    };
                    tags.push({ key: 'onlineStatus', label: '在线状态', value: onlineStatusMap[this.filters.onlineStatus] || this.filters.onlineStatus });
                }

                return tags;
            }
        })

    }
});

// Initialize the Vue app
accountTable.createApp('#app');
