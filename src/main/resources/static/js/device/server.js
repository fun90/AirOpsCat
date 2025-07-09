
import { DataTable } from '/static/js/common/data-table.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';

const serverTable = new DataTable({
    data: {
        entityName: 'servers',
        modalIdPrefix: 'server-',
        stats: {
            total: 0,
            active: 0,
            expired: 0,
            disabled: 0,
            expiringSoon: 0
        },
        filters: {
            supplier: '',
            status: ''
        },
        supplierStats: {},
        totalCost: 0,
        totalEffectiveCost: 0,
        authTypes: [],
        testingConnection: false,
        connectionTestResult: null,
        renewData: {
            expiryDate: ''
        },
        transitConfigJson: '',
        coreConfigJson: '',
        editTransitConfigJson: '',
        editCoreConfigJson: '',
        newItem: {
            ip: '',
            sshPort: 22,
            username: 'root',
            authType: 'PASSWORD',
            auth: '',
            host: '',
            name: '',
            expireDate: '',
            supplier: '',
            price: '',
            multiple: 1,
            disabled: false,
            remark: '',
            transitConfig: null,
            coreConfig: null
        }
    },
    methods: {
        // Initialize any additional data
        initialize() {
            this.fetchAuthTypes();
        },

        // Fetch authentication types
        fetchAuthTypes() {
            fetch('/api/admin/servers/auth-types')
                .then(response => response.json())
                .then(data => {
                    this.authTypes = data;

                    // Set default auth type if available
                    if (this.authTypes.length > 0) {
                        this.newItem.authType = this.authTypes[0].value;
                    }
                })
                .catch(error => {
                    console.error('Error fetching auth types:', error);
                });
        },

        getStatusDescription(item) {
            if (item.disabled != null && item.disabled === 1) {
                return "已禁用";
            }

            if (item.expireDate == null) {
                return "永不过期";
            }

            const now = new Date();
            const expireDate = new Date(item.expireDate);

            // 计算天数差
            const timeDiff = expireDate.getTime() - now.getTime();
            const days = Math.floor(timeDiff / (1000 * 60 * 60 * 24));

            if (days < 0) {
                return "已过期 " + Math.abs(days) + " 天";
            } else if (days === 0) {
                return "今天过期"
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

            if (item.expireDate == null) {
                return "active";
            }

            const now = new Date();
            const expireDate = new Date(item.expireDate);

            // 计算天数差
            const timeDiff = expireDate.getTime() - now.getTime();
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

        getAuthTypeLabel(authType) {
            const type = this.authTypes.find(t => t.value === authType);
            return type ? type.label : authType;
        },

        // Form validation and preparation
        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            // IP validation
            if (!this.newItem.ip || !this.newItem.ip.trim()) {
                this.validationErrors.ip = 'IP地址不能为空';
                isValid = false;
            }

            // Username validation
            if (!this.newItem.username || !this.newItem.username.trim()) {
                this.validationErrors.username = '用户名不能为空';
                isValid = false;
            }

            // Auth type validation
            if (!this.newItem.authType) {
                this.validationErrors.authType = '请选择认证方式';
                isValid = false;
            }

            // Auth validation
            if (this.newItem.authType && !this.newItem.auth) {
                this.validationErrors.auth = '认证信息不能为空';
                isValid = false;
            }

            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            // IP validation
            if (!this.editedItem.ip || !this.editedItem.ip.trim()) {
                this.validationErrors.ip = 'IP地址不能为空';
                isValid = false;
            }

            // Username validation
            if (!this.editedItem.username || !this.editedItem.username.trim()) {
                this.validationErrors.username = '用户名不能为空';
                isValid = false;
            }

            // Auth type validation
            if (!this.editedItem.authType) {
                this.validationErrors.authType = '请选择认证方式';
                isValid = false;
            }

            // Auth validation
            if (this.editedItem.authType && !this.editedItem.auth) {
                this.validationErrors.auth = '认证信息不能为空';
                isValid = false;
            }

            return isValid;
        },

        prepareCreateData() {
            // Process JSON configs
            let transitConfig = null;
            let coreConfig = null;

            try {
                if (this.transitConfigJson) {
                    transitConfig = JSON.parse(this.transitConfigJson);
                }
                if (this.coreConfigJson) {
                    coreConfig = JSON.parse(this.coreConfigJson);
                }
            } catch (e) {
                ToastUtils.show('Error', 'JSON配置格式错误: ' + e.message, 'danger');
                return null;
            }

            return {
                ip: this.newItem.ip,
                sshPort: this.newItem.sshPort || 22,
                username: this.newItem.username || 'root',
                authType: this.newItem.authType,
                auth: this.newItem.auth,
                host: this.newItem.host || null,
                name: this.newItem.name || null,
                expireDate: this.newItem.expireDate || null,
                supplier: this.newItem.supplier || null,
                price: this.newItem.price || null,
                multiple: this.newItem.multiple || 1,
                disabled: this.newItem.disabled ? 1 : 0,
                remark: this.newItem.remark || null,
                transitConfig: transitConfig,
                coreConfig: coreConfig
            };
        },

        prepareUpdateData() {
            // Process JSON configs
            let transitConfig = null;
            let coreConfig = null;

            try {
                if (this.editTransitConfigJson) {
                    transitConfig = JSON.parse(this.editTransitConfigJson);
                }
                if (this.editCoreConfigJson) {
                    coreConfig = JSON.parse(this.editCoreConfigJson);
                }
            } catch (e) {
                ToastUtils.show('Error', 'JSON配置格式错误: ' + e.message, 'danger');
                return null;
            }

            return {
                ip: this.editedItem.ip,
                sshPort: this.editedItem.sshPort || 22,
                username: this.editedItem.username || 'root',
                authType: this.editedItem.authType,
                auth: this.editedItem.auth,
                host: this.editedItem.host || null,
                name: this.editedItem.name || null,
                expireDate: this.editedItem.expireDate || null,
                supplier: this.editedItem.supplier || null,
                price: this.editedItem.price || null,
                multiple: this.editedItem.multiple || 1,
                disabled: this.editedItem.disabled,
                remark: this.editedItem.remark || null,
                transitConfig: transitConfig,
                coreConfig: coreConfig
            };
        },

        resetCreateForm() {
            // Get one month later for default expiration date
            const oneMonthLater = new Date();
            oneMonthLater.setMonth(oneMonthLater.getMonth() + 1);
            const oneMonthLaterFormat = oneMonthLater.toISOString().split('T')[0];

            this.newItem = {
                ip: '',
                sshPort: 22,
                username: 'root',
                authType: this.authTypes.length > 0 ? this.authTypes[0].value : 'PASSWORD',
                auth: '',
                host: '',
                name: '',
                expireDate: oneMonthLaterFormat,
                supplier: '',
                price: '',
                multiple: 1,
                disabled: false,
                remark: '',
                transitConfig: null,
                coreConfig: null
            };

            this.transitConfigJson = '';
            this.coreConfigJson = '';
        },

        prepareEditForm(server) {
            // Format dates for input
            const formatDateForInput = (dateString) => {
                if (!dateString) return '';
                const date = new Date(dateString);
                return date.toISOString().split('T')[0]; // Format: YYYY-MM-DD
            };

            // Set JSON configs
            this.editTransitConfigJson = server.transitConfig ?
                JSON.stringify(server.transitConfig, null, 2) : '';
            this.editCoreConfigJson = server.coreConfig ?
                JSON.stringify(server.coreConfig, null, 2) : '';

            return {
                id: server.id,
                ip: server.ip,
                sshPort: server.sshPort || 22,
                username: server.username || 'root',
                authType: server.authType,
                auth: server.auth,
                host: server.host || '',
                name: server.name || '',
                expireDate: formatDateForInput(server.expireDate),
                supplier: server.supplier || '',
                price: server.price,
                multiple: server.multiple || 1,
                disabled: server.disabled,
                remark: server.remark || '',
                transitConfig: server.transitConfig,
                coreConfig: server.coreConfig
            };
        },

        afterFetch(data) {
            // Additional processing after fetch
            if (data.supplierStats) {
                this.supplierStats = data.supplierStats;
            }
            if (data.totalCost !== undefined) {
                this.totalCost = data.totalCost;
            }
            if (data.totalEffectiveCost !== undefined) {
                this.totalEffectiveCost = data.totalEffectiveCost;
            }
        },

        // Test connection functionality
        openTestConnectionModal(server) {
            this.selectedItem = server;
            this.connectionTestResult = null;
            this.testingConnection = false;

            this.testConnectionModal = new Modal(document.getElementById('testConnectionModal'));
            this.testConnectionModal.show();
        },

        testConnection() {
            if (!this.selectedItem) return;

            this.testingConnection = true;

            fetch('/api/admin/servers/test-connection', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(this.selectedItem)
            })
                .then(response => response.json())
                .then(data => {
                    this.connectionTestResult = data.success;
                    this.testingConnection = false;
                })
                .catch(error => {
                    console.error('Error:', error);
                    this.connectionTestResult = false;
                    this.testingConnection = false;
                });
        },

        resetConnectionTest() {
            this.connectionTestResult = null;
        },

        // Renew server functionality
        openRenewServerModal(server) {
            this.selectedItem = server;

            // Calculate default renewal date (from current time or expiry time +1 month)
            let baseDate;
            if (server.expireDate && new Date(server.expireDate) > new Date()) {
                baseDate = new Date(server.expireDate);
            } else {
                baseDate = new Date();
            }

            // Add one month
            baseDate.setMonth(baseDate.getMonth() + 1);
            this.renewData = {
                expiryDate: baseDate.toISOString().split('T')[0] // Format: YYYY-MM-DD
            };

            this.validationErrors = {};
            this.renewModal = new Modal(document.getElementById('renewServerModal'));
            this.renewModal.show();
        },

        setRenewPeriod(value, unit) {
            // Calculate new renewal time (from current time or expiry time)
            let baseDate;
            if (this.selectedItem.expireDate && new Date(this.selectedItem.expireDate) > new Date()) {
                baseDate = new Date(this.selectedItem.expireDate);
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

            this.renewData.expiryDate = baseDate.toISOString().split('T')[0];
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

        renewServer() {
            if (!this.validateRenewForm()) {
                return;
            }

            fetch(`/api/admin/servers/${this.selectedItem.id}/renew?expiryDate=${encodeURIComponent(this.renewData.expiryDate)}`, {
                method: 'PATCH'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('续期失败');
                    }
                    return response.json();
                })
                .then(data => {
                    // Refresh the server list
                    this.fetchRecords();
                    this.renewModal.hide();
                    ToastUtils.show('Success', '续期成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '续期失败', 'danger');
                });
        },

        // URLs for API calls
        getApiUrl() {
            return '/api/admin/servers';
        },

        getToggleStatusUrl(item, action) {
            return `/api/admin/servers/${item.id}/${action}`;
        }
    }
});

// Initialize the Vue app
serverTable.createApp('#servers-app');