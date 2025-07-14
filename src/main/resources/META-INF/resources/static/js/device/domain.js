import { DataTable } from '/static/js/common/data-table.js';

const domainTable = new DataTable({
    data: {
        entityName: 'domains',
        modalIdPrefix: 'domain-',
        stats: {
            expiredCount: 0,
            expiringCount: 0,
            totalCost: 0
        },
        filters: {
            expiryFrom: '',
            expiryTo: ''
        },
        newItem: {
            domain: '',
            expireDate: '',
            price: '',
            remark: ''
        }
    },
    methods: {
        // Initialize with default expiry date for new domains
        initialize() {
            // Get one year from now for default expiration date
            const today = new Date();
            today.setFullYear(today.getFullYear() + 1); // Add one year
            const oneYearFromNow = today.toISOString().split('T')[0];

            // Set default expiry date
            this.newItem.expireDate = oneYearFromNow;
        },

        // Domain status methods
        getDomainStatus(daysUntilExpiration) {
            if (daysUntilExpiration === undefined || daysUntilExpiration === null) {
                return "未设置到期日";
            }

            if (daysUntilExpiration < 0) {
                return "已过期 " + Math.abs(daysUntilExpiration) + " 天";
            } else if (daysUntilExpiration === 0) {
                return "今天到期";
            } else if (daysUntilExpiration <= 30) {
                return "即将到期 " + daysUntilExpiration + " 天";
            } else {
                return "正常 (还有 " + daysUntilExpiration + " 天)";
            }
        },

        getStatusBadgeClass(daysUntilExpiration) {
            if (daysUntilExpiration === undefined || daysUntilExpiration === null) {
                return "text-bg-secondary";
            }

            if (daysUntilExpiration < 0) {
                return "text-bg-danger";
            } else if (daysUntilExpiration <= 30) {
                return "text-bg-warning";
            } else {
                return "text-bg-success";
            }
        },

        // Form validation and preparation
        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            // Domain validation
            if (!this.newItem.domain || !this.newItem.domain.trim()) {
                this.validationErrors.domain = '域名不能为空';
                isValid = false;
            } else if (!/^[a-zA-Z0-9][a-zA-Z0-9-]{1,61}[a-zA-Z0-9](?:\.[a-zA-Z]{2,})+$/.test(this.newItem.domain)) {
                this.validationErrors.domain = '请输入有效的域名';
                isValid = false;
            }

            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            // Domain validation
            if (!this.editedItem.domain || !this.editedItem.domain.trim()) {
                this.validationErrors.domain = '域名不能为空';
                isValid = false;
            } else if (!/^[a-zA-Z0-9][a-zA-Z0-9-]{1,61}[a-zA-Z0-9](?:\.[a-zA-Z]{2,})+$/.test(this.editedItem.domain)) {
                this.validationErrors.domain = '请输入有效的域名';
                isValid = false;
            }

            return isValid;
        },

        prepareCreateData() {
            return {
                domain: this.newItem.domain,
                expireDate: this.newItem.expireDate || null,
                price: this.newItem.price || null,
                remark: this.newItem.remark || null
            };
        },

        prepareUpdateData() {
            return {
                domain: this.editedItem.domain,
                expireDate: this.editedItem.expireDate || null,
                price: this.editedItem.price || null,
                remark: this.editedItem.remark || null
            };
        },

        resetCreateForm() {
            // Get one year from now for default expiration date
            const today = new Date();
            today.setFullYear(today.getFullYear() + 1); // Add one year
            const oneYearFromNow = today.toISOString().split('T')[0];

            this.newItem = {
                domain: '',
                expireDate: oneYearFromNow,
                price: '',
                remark: ''
            };
        },

        prepareEditForm(domain) {
            // Format date for date input
            const formatDateForInput = (dateString) => {
                if (!dateString) return '';
                const date = new Date(dateString);
                return date.toISOString().split('T')[0]; // Format: YYYY-MM-DD
            };

            return {
                id: domain.id,
                domain: domain.domain,
                expireDate: formatDateForInput(domain.expireDate),
                price: domain.price,
                remark: domain.remark || ''
            };
        },

        // URLs for API calls
        getApiUrl() {
            return '/api/admin/domains';
        }
    }
});

// Initialize the Vue app
domainTable.createApp('#app');