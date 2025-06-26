import { DataTable } from '/static/js/common/data-table.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';

const accountTable = new DataTable({
    data: {
        entityName: 'accounts',
        modalIdPrefix: 'account-',
        filters: {
            userId: '',
            status: ''
        },
        users: [],
        periodTypes: [],
        availableTags: [],
        stats: {
            total: 0,
            active: 0,
            expired: 0,
            disabled: 0,
            expiringSoon: 0
        },
        configUrl: '',
        configUrlModal: null,
        configOptions: {
            osName: '',
            appName: ''
        },
        // 操作系统和应用的关联关系
        osAppMapping: {
            'windows': ['clash-verge'],
            'macos': ['clash-verge'],
            'linux': ['clash-verge'],
            'android': ['clash-meta'],
            'harmony': ['clash-meta'],
            'ios': ['shadowrocket', 'loon', 'stash']
        },
        // 所有可用的应用列表
        allApps: [
            { value: 'clash-verge', label: 'Clash Verge' },
            { value: 'clash-meta', label: 'Clash Meta' },
            { value: 'stash', label: 'Stash' },
            { value: 'shadowrocket', label: 'Shadowrocket' },
            { value: 'loon', label: 'Loon' },
            { value: 'v2rayng', label: 'V2rayNG' }
        ],
        availableApps: [],
        renewModal: null,
        renewData: {
            expiryDate: ''
        },
        resetAuthCodeModal: null,
        newItem: {
            userId: '',
            level: 0,
            fromDate: '',
            toDate: '',
            periodType: 'MONTHLY',
            uuid: '',
            authCode: '',
            accountNo: '',
            maxOnlineIps: 0,
            speed: 0,
            bandwidth: 0,
            disabled: false,
            tagIds: []
        }
    },
    methods: {
        // Initialize any additional data
        initialize() {
            this.fetchUsers();
            this.fetchPeriodTypes();
            this.fetchAvailableTags();
        },

        // Fetch additional data
        fetchUsers() {
            fetch('/api/admin/users')
                .then(response => response.json())
                .then(data => {
                    this.users = data.records;

                    // If it's the first load and no user selected, select the first one
                    if (this.users.length > 0 && !this.newItem.userId) {
                        this.newItem.userId = this.users[0].id;
                    }
                })
                .catch(error => {
                    console.error('Error fetching users:', error);
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
                case 'active': return 'text-bg-success';
                case 'expired': return 'text-bg-danger';
                case 'disabled': return 'text-bg-secondary';
                default: return 'text-bg-secondary';
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

        // Navigation and UI actions
        viewUserDetails(userId) {
            // Redirect to user details page
            window.location.href = `/admin/users/${userId}`;
        },

        // UUID and Auth code management
        generateUuid() {
            this.newItem.uuid = this.uuidv4();
        },

        generateAuthCode() {
            this.newItem.authCode = this.generateRandomString(16);
        },

        regenerateAuthCode() {
            if (!this.editedItem.id) return;

            // 显示确认弹窗
            this.resetAuthCodeModal = new Modal(document.getElementById('resetAuthCodeModal'));
            this.resetAuthCodeModal.show();
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
                expiryDate: baseDate.toISOString().slice(0, 16) // Format: YYYY-MM-DDTHH:MM
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

            this.renewData.expiryDate = baseDate.toISOString().slice(0, 16);
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

            return isValid;
        },

        renewAccount() {
            if (!this.validateRenewForm()) {
                return;
            }

            fetch(`/api/admin/accounts/${this.selectedItem.id}/renew?expiryDate=${encodeURIComponent(this.renewData.expiryDate)}`, {
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

            return isValid;
        },

        prepareCreateData() {
            // Get current time
            const now = new Date();
            const localDateTimeFormat = now.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM

            // Get one month later for default expiration
            const oneMonthLater = new Date();
            oneMonthLater.setMonth(oneMonthLater.getMonth() + 1);
            const oneMonthLaterFormat = oneMonthLater.toISOString().slice(0, 16);

            return {
                userId: this.newItem.userId,
                accountNo: this.newItem.accountNo,
                level: this.newItem.level || null,
                fromDate: this.newItem.fromDate || localDateTimeFormat,
                toDate: this.newItem.toDate || oneMonthLaterFormat,
                periodType: this.newItem.periodType,
                uuid: this.newItem.uuid || null, // Will be generated on server if null
                authCode: this.newItem.authCode || null, // Will be generated on server if null
                maxOnlineIps: this.newItem.maxOnlineIps || null,
                speed: this.newItem.speed || null,
                bandwidth: this.newItem.bandwidth || null,
                disabled: this.newItem.disabled ? 1 : 0,
                tagIds: this.newItem.tagIds || []
            };
        },

        prepareUpdateData() {
            return {
                userId: this.editedItem.userId,
                accountNo: this.editedItem.accountNo,
                level: this.editedItem.level,
                fromDate: this.editedItem.fromDate || null,
                toDate: this.editedItem.toDate || null,
                periodType: this.editedItem.periodType,
                maxOnlineIps: this.editedItem.maxOnlineIps,
                speed: this.editedItem.speed,
                bandwidth: this.editedItem.bandwidth,
                disabled: this.editedItem.disabled,
                tagIds: this.editedItem.tagIds || []
            };
        },

        resetCreateForm() {
            // Get current time
            const now = new Date();
            const localDateTimeFormat = now.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM

            // Get one month later for default expiration
            const oneMonthLater = new Date();
            oneMonthLater.setMonth(oneMonthLater.getMonth() + 1);
            const oneMonthLaterFormat = oneMonthLater.toISOString().slice(0, 16);

            this.newItem = {
                userId: this.users.length > 0 ? this.users[0].id : '',
                accountNo: '',
                level: 0,
                fromDate: localDateTimeFormat,
                toDate: oneMonthLaterFormat,
                periodType: 'MONTHLY',
                uuid: '',
                authCode: '',
                maxOnlineIps: 0,
                speed: 0,
                bandwidth: 0,
                disabled: false,
                tagIds: []
            };
        },

        prepareEditForm(account) {
            // Format dates for datetime-local input
            const formatDateForInput = (dateString) => {
                if (!dateString) return '';
                const date = new Date(dateString);
                return date.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM
            };

            // Load current account tags
            this.loadAccountTags(account.id);

            return {
                id: account.id,
                userId: account.userId,
                accountNo: account.accountNo,
                level: account.level,
                fromDate: formatDateForInput(account.fromDate),
                toDate: formatDateForInput(account.toDate),
                periodType: account.periodType,
                uuid: account.uuid,
                authCode: account.authCode,
                maxOnlineIps: account.maxOnlineIps,
                speed: account.speed,
                bandwidth: account.bandwidth,
                disabled: account.disabled,
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

        getToggleStatusUrl(item, action) {
            return `/api/admin/accounts/${item.id}/${action}`;
        }
    }
});

// Initialize the Vue app
accountTable.createApp('#accounts-app');