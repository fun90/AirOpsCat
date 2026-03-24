
import { DataTable } from '/static/js/common/data-table.js';
import { Modal, Tooltip } from '/static/tabler/js/tabler.esm.min.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';

const DEFAULT_SERVER_FILTERS = Object.freeze({
    supplier: '',
    status: ''
});

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
        filters: { ...DEFAULT_SERVER_FILTERS },
        supplierStats: {},
        totalCost: 0,
        totalEffectiveCost: 0,
        authTypes: [],
        paymentMethods: [],
        testingConnection: false,
        connectionTestResult: null,
        previewConfigModal: null,
        previewConfigLoading: false,
        previewConfigCoreTypes: [],
        previewConfigSelectedCoreType: '',
        previewConfigs: {},
        renewData: {
            expiryDate: '',
            amount: '',
            paymentMethod: ''
        },
        trafficCalibration: {
            uploadGb: '',
            downloadGb: '',
            periodStartDate: '',
            periodEndDate: ''
        },
        hostsText: '',
        editHostsText: '',
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
                hosts: [],
                name: '',
                expireDate: '',
                bandwidthDate: '',
                supplier: '',
                price: '',
                multiple: 1,
                bandwidth: '',
                disabled: false,
                external: false,
                remark: '',
                transitConfig: null,
                coreConfig: null
            }
    },
    methods: {
        // Initialize any additional data
        initialize() {
            this.fetchAuthTypes();
            this.fetchPaymentMethods();
        },

        handleFilterChange() {
            this.currentPage = 1;
            this.fetchRecords();
        },

        getSupplierFilterValue(supplier) {
            return supplier === '未知' ? '__UNKNOWN__' : supplier;
        },

        fetchRecords() {
            this.loading = true;

            const params = new URLSearchParams({
                page: this.currentPage,
                size: this.pageSize
            });

            if (this.searchQuery) {
                params.append('search', this.searchQuery);
            }

            if (this.filters.supplier) {
                params.append('supplier', this.filters.supplier);
            }

            switch (this.filters.status) {
                case 'active':
                    params.append('expired', 'false');
                    params.append('disabled', 'false');
                    break;
                case 'expired':
                    params.append('expired', 'true');
                    params.append('disabled', 'false');
                    break;
                case 'disabled':
                    params.append('disabled', 'true');
                    break;
                default:
                    break;
            }

            fetch(`/api/admin/${this.entityName}?${params.toString()}`)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('Network response was not ok');
                    }
                    return response.json();
                })
                .then(data => {
                    this.records = data.records || [];
                    this.totalItems = data.total || 0;
                    this.startIndex = (this.currentPage - 1) * this.pageSize + 1;
                    this.endIndex = Math.min(this.startIndex + this.pageSize - 1, this.totalItems);
                    this.totalPages = data.pages || 0;
                    this.currentPage = data.current || 1;

                    if (data.stats) {
                        this.stats = data.stats;
                    }

                    this.loading = false;

                    this.$nextTick(() => {
                        const existingTooltips = document.querySelectorAll('[data-bs-toggle="tooltip"]');
                        existingTooltips.forEach(element => {
                            const tooltipInstance = Tooltip.getInstance(element);
                            if (tooltipInstance) {
                                tooltipInstance.dispose();
                            }
                        });

                        const tooltipTriggerList = [].slice.call(document.querySelectorAll('[data-bs-toggle="tooltip"]'));
                        tooltipTriggerList.map(function (tooltipTriggerEl) {
                            return new Tooltip(tooltipTriggerEl);
                        });
                    });

                    if (typeof this.afterFetch === 'function') {
                        this.afterFetch(data);
                    }
                })
                .catch(error => {
                    console.error(`Error fetching ${this.entityName || 'records'}:`, error);
                    ToastUtils.show('Error', `Failed to load ${this.entityName || 'records'}.`, 'danger');
                    this.loading = false;
                });
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
                host: this.getPrimaryHostFromText(this.hostsText) || this.newItem.host || null,
                primaryHost: this.getPrimaryHostFromText(this.hostsText) || this.newItem.host || null,
                hosts: this.parseHostsText(this.hostsText),
                name: this.newItem.name || null,
                expireDate: this.newItem.expireDate || null,
                bandwidthDate: this.newItem.bandwidthDate || null,
                supplier: this.newItem.supplier || null,
                price: this.newItem.price || null,
                multiple: this.newItem.multiple || 1,
                bandwidth: this.newItem.bandwidth || null,
                disabled: this.newItem.disabled ? 1 : 0,
                external: this.newItem.external ? 1 : 0,
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
                host: this.getPrimaryHostFromText(this.editHostsText) || this.editedItem.host || null,
                primaryHost: this.getPrimaryHostFromText(this.editHostsText) || this.editedItem.host || null,
                hosts: this.parseHostsText(this.editHostsText),
                name: this.editedItem.name || null,
                expireDate: this.editedItem.expireDate || null,
                bandwidthDate: this.editedItem.bandwidthDate || null,
                supplier: this.editedItem.supplier || null,
                price: this.editedItem.price || null,
                multiple: this.editedItem.multiple || 1,
                bandwidth: this.editedItem.bandwidth || null,
                disabled: this.editedItem.disabled,
                external: this.editedItem.external,
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
                hosts: [],
                name: '',
                expireDate: oneMonthLaterFormat,
                supplier: '',
                price: '',
                multiple: 1,
                bandwidth: '',
                disabled: false,
                external: false,
                remark: '',
                transitConfig: null,
                coreConfig: null
            };

            this.transitConfigJson = '';
            this.coreConfigJson = '';
            this.hostsText = '';
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
            this.editHostsText = this.serializeHosts(server.hosts, server.host);

            return {
                id: server.id,
                ip: server.ip,
                sshPort: server.sshPort || 22,
                username: server.username || 'root',
                authType: server.authType,
                auth: server.auth,
                host: server.primaryHost || server.host || '',
                hosts: server.hosts || [],
                name: server.name || '',
                expireDate: formatDateForInput(server.expireDate),
                bandwidthDate: formatDateForInput(server.bandwidthDate),
                supplier: server.supplier || '',
                price: server.price,
                multiple: server.multiple || 1,
                bandwidth: server.bandwidth || '',
                disabled: server.disabled,
                external: server.external,
                remark: server.remark || '',
                transitConfig: server.transitConfig,
                coreConfig: server.coreConfig
            };
        },

        parseHostsText(text) {
            const values = (text || '')
                .split(/\r?\n/)
                .map(item => item.trim())
                .filter(Boolean);

            return values.map((host, index) => ({
                host,
                isPrimary: index === 0 ? 1 : 0,
                enabled: 1,
                sort: index
            }));
        },

        getPrimaryHostFromText(text) {
            const hosts = this.parseHostsText(text);
            return hosts.length > 0 ? hosts[0].host : null;
        },

        serializeHosts(hosts, fallbackHost) {
            if (Array.isArray(hosts) && hosts.length > 0) {
                return hosts
                    .map(host => host?.host)
                    .filter(Boolean)
                    .join('\n');
            }
            return fallbackHost || '';
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

        openConfigPreviewModal(server) {
            this.selectedItem = server;
            this.previewConfigLoading = true;
            this.previewConfigCoreTypes = [];
            this.previewConfigSelectedCoreType = '';
            this.previewConfigs = {};

            this.previewConfigModal = new Modal(document.getElementById('configPreviewModal'));
            this.previewConfigModal.show();

            fetch(`/api/admin/servers/${server.id}/config-preview`)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('加载配置预览失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.previewConfigs = data.configs || {};
                    this.previewConfigCoreTypes = Array.isArray(data.coreTypes) ? data.coreTypes : Object.keys(this.previewConfigs);
                    this.previewConfigSelectedCoreType = this.previewConfigCoreTypes.length > 0 ? this.previewConfigCoreTypes[0] : '';
                    this.previewConfigLoading = false;
                })
                .catch(error => {
                    console.error('Error:', error);
                    this.previewConfigLoading = false;
                    ToastUtils.show('Error', error.message || '加载配置预览失败', 'danger');
                });
        },

        getPreviewConfigContent() {
            if (!this.previewConfigSelectedCoreType) {
                return '';
            }
            return this.previewConfigs[this.previewConfigSelectedCoreType] || '';
        },

        copyPreviewConfig() {
            const content = this.getPreviewConfigContent();
            if (!content) {
                ToastUtils.show('Warning', '当前没有可复制的配置', 'warning');
                return;
            }
            this.copyToClipboard(content);
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
                expiryDate: baseDate.toISOString().split('T')[0], // Format: YYYY-MM-DD
                amount: '',
                paymentMethod: ''
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

        openTrafficCalibrationModal(server) {
            this.selectedItem = server;
            this.validationErrors = {};

            const inferredPeriod = this.inferTrafficPeriod(server);
            this.trafficCalibration = {
                uploadGb: this.bytesToGbValue(server.trafficUploadBytes),
                downloadGb: this.bytesToGbValue(server.trafficDownloadBytes),
                periodStartDate: inferredPeriod.start,
                periodEndDate: inferredPeriod.end
            };

            this.trafficCalibrationModal = new Modal(document.getElementById('trafficCalibrationModal'));
            this.trafficCalibrationModal.show();
        },

        inferTrafficPeriod(server) {
            if (server.trafficPeriodStart && server.trafficPeriodEnd) {
                return {
                    start: this.formatDateInput(server.trafficPeriodStart),
                    end: this.formatDateInput(server.trafficPeriodEnd)
                };
            }

            const today = new Date();
            const billingDay = server.bandwidthDate
                ? new Date(server.bandwidthDate).getDate()
                : today.getDate();

            let start = new Date(today.getFullYear(), today.getMonth(), billingDay);
            if (start > today) {
                start = new Date(today.getFullYear(), today.getMonth() - 1, billingDay);
            }
            const end = new Date(start.getFullYear(), start.getMonth() + 1, start.getDate() - 1);

            return {
                start: this.formatDateInput(start),
                end: this.formatDateInput(end)
            };
        },

        formatDateInput(dateValue) {
            if (!dateValue) return '';
            const date = new Date(dateValue);
            const year = date.getFullYear();
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const day = String(date.getDate()).padStart(2, '0');
            return `${year}-${month}-${day}`;
        },

        bytesToGbValue(bytes) {
            const value = Number(bytes || 0) / (1024 * 1024 * 1024);
            return Number.isFinite(value) ? value.toFixed(2) : '0.00';
        },

        validateTrafficCalibrationForm() {
            let isValid = true;
            this.validationErrors = {};

            const uploadGb = parseFloat(this.trafficCalibration.uploadGb);
            if (this.trafficCalibration.uploadGb === '' || Number.isNaN(uploadGb) || uploadGb < 0) {
                this.validationErrors.trafficUploadGb = '请输入有效的上传流量';
                isValid = false;
            }

            const downloadGb = parseFloat(this.trafficCalibration.downloadGb);
            if (this.trafficCalibration.downloadGb === '' || Number.isNaN(downloadGb) || downloadGb < 0) {
                this.validationErrors.trafficDownloadGb = '请输入有效的下载流量';
                isValid = false;
            }

            if (!this.trafficCalibration.periodStartDate) {
                this.validationErrors.trafficPeriodStartDate = '请选择周期开始日期';
                isValid = false;
            }

            if (!this.trafficCalibration.periodEndDate) {
                this.validationErrors.trafficPeriodEndDate = '请选择周期结束日期';
                isValid = false;
            }

            if (this.trafficCalibration.periodStartDate && this.trafficCalibration.periodEndDate
                && this.trafficCalibration.periodEndDate < this.trafficCalibration.periodStartDate) {
                this.validationErrors.trafficPeriodEndDate = '周期结束日期不能早于开始日期';
                isValid = false;
            }

            return isValid;
        },

        saveTrafficCalibration() {
            if (!this.selectedItem || !this.validateTrafficCalibrationForm()) {
                return;
            }

            fetch(`/api/admin/servers/${this.selectedItem.id}/traffic-calibration`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    uploadGb: this.trafficCalibration.uploadGb,
                    downloadGb: this.trafficCalibration.downloadGb,
                    periodStartDate: this.trafficCalibration.periodStartDate,
                    periodEndDate: this.trafficCalibration.periodEndDate
                })
            })
                .then(response => {
                    if (!response.ok) {
                        if (response.status === 400) {
                            return response.json().then(data => {
                                throw new Error(data.message || '流量校准失败');
                            });
                        }
                        throw new Error('流量校准失败');
                    }
                    return response.json();
                })
                .then(() => {
                    this.fetchRecords();
                    this.trafficCalibrationModal.hide();
                    ToastUtils.show('Success', '流量校准成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '流量校准失败', 'danger');
                });
        },

        renewServer() {
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

            fetch(`/api/admin/servers/${this.selectedItem.id}/renew?${params.toString()}`, {
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
        },

        ...createResponsiveFilterMethods({
            createDefaultFilters: () => ({ ...DEFAULT_SERVER_FILTERS }),
            getActiveTags() {
                const tags = this.searchQuery ? [{
                    key: 'search',
                    label: '搜索',
                    value: this.searchQuery
                }] : [];

                if (this.filters.supplier) {
                    tags.push({ key: 'supplier', label: '供应商', value: this.filters.supplier === '__UNKNOWN__' ? '未知' : this.filters.supplier });
                }

                if (this.filters.status) {
                    const statusMap = {
                        active: '活跃',
                        expired: '已过期',
                        disabled: '已禁用'
                    };
                    tags.push({ key: 'status', label: '状态', value: statusMap[this.filters.status] || this.filters.status });
                }

                return tags;
            }
        })
    }
});

// Initialize the Vue app
serverTable.createApp('#app');
