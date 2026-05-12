import { DataTable } from '/static/js/common/data-table.js';
import { formatDateTimeForLocal, formatRelativeTime, formatDateTimeFull } from '/static/js/common/common.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';
import { createUserSearch } from '/static/js/common/tom-select-helper.js';

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
            this.accountDetailsModal = new Modal(document.getElementById('accountDetailsModal'));
            this.accountDetailsModal.show();
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
                uuid: this.editedItem.uuid
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
