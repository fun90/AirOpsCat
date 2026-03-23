import { DataTable } from '/static/js/common/data-table.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';

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
        paymentMethods: [],
        renewData: {
            expiryDate: '',
            amount: '',
            paymentMethod: ''
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
            this.fetchPaymentMethods();

            // Get one year from now for default expiration date
            const today = new Date();
            today.setFullYear(today.getFullYear() + 1); // Add one year
            const oneYearFromNow = today.toISOString().split('T')[0];

            // Set default expiry date
            this.newItem.expireDate = oneYearFromNow;
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
                return "bg-secondary-lt";
            }

            if (daysUntilExpiration < 0) {
                return "bg-danger-lt";
            } else if (daysUntilExpiration <= 30) {
                return "bg-warning-lt";
            } else {
                return "bg-success-lt";
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

        // Renew domain methods
        openRenewDomainModal(domain) {
            this.selectedItem = domain;

            let baseDate;
            if (domain.expireDate && new Date(domain.expireDate) > new Date()) {
                baseDate = new Date(domain.expireDate);
            } else {
                baseDate = new Date();
            }

            baseDate.setMonth(baseDate.getMonth() + 1);
            this.renewData = {
                expiryDate: baseDate.toISOString().split('T')[0],
                amount: '',
                paymentMethod: ''
            };

            this.validationErrors = {};
            this.renewModal = new Modal(document.getElementById('renewDomainModal'));
            this.renewModal.show();
        },

        setRenewPeriod(value, unit) {
            let baseDate;
            if (this.selectedItem.expireDate && new Date(this.selectedItem.expireDate) > new Date()) {
                baseDate = new Date(this.selectedItem.expireDate);
            } else {
                baseDate = new Date();
            }

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

        renewDomain() {
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

            fetch(`/api/admin/domains/${this.selectedItem.id}/renew?${params.toString()}`, {
                method: 'PATCH'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('续期失败');
                    }
                    return response.json();
                })
                .then(() => {
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
            return '/api/admin/domains';
        }
    }
});

// Initialize the Vue app
domainTable.createApp('#app');
