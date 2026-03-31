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
        dnsProviders: [],
        dnsBindingData: {
            dnsProviderConfigId: '',
            zoneId: ''
        },
        pullingDomainIds: [],
        pushingDomainIds: [],
        dnsBindingModal: null,
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
        initialize() {
            this.fetchPaymentMethods();
            this.fetchDnsProviders();

            const today = new Date();
            today.setFullYear(today.getFullYear() + 1);
            this.newItem.expireDate = today.toISOString().split('T')[0];
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

        fetchDnsProviders() {
            fetch('/api/admin/dns-provider-configs/enabled')
                .then(response => response.json())
                .then(data => {
                    this.dnsProviders = data || [];
                })
                .catch(error => {
                    console.error('Error fetching dns providers:', error);
                    this.dnsProviders = [];
                });
        },

        getDomainStatus(daysUntilExpiration) {
            if (daysUntilExpiration === undefined || daysUntilExpiration === null) {
                return '未设置到期日';
            }
            if (daysUntilExpiration < 0) {
                return `已过期 ${Math.abs(daysUntilExpiration)} 天`;
            }
            if (daysUntilExpiration === 0) {
                return '今天到期';
            }
            if (daysUntilExpiration <= 30) {
                return `即将到期 ${daysUntilExpiration} 天`;
            }
            return `正常 (还有 ${daysUntilExpiration} 天)`;
        },

        getStatusBadgeClass(daysUntilExpiration) {
            if (daysUntilExpiration === undefined || daysUntilExpiration === null) {
                return 'bg-secondary-lt';
            }
            if (daysUntilExpiration < 0) {
                return 'bg-danger-lt';
            }
            if (daysUntilExpiration <= 30) {
                return 'bg-warning-lt';
            }
            return 'bg-success-lt';
        },

        getDnsSyncDescription(domain) {
            if (!domain.dnsProviderConfigId) return '未绑定';
            if (domain.dnsSyncStatus === 'SYNCED') return '已同步';
            if (domain.dnsSyncStatus === 'NOT_SYNCED') return '未同步';
            if (domain.dnsSyncStatus === 'PULLING') return '拉取中';
            if (domain.dnsSyncStatus === 'PUSHING') return '推送中';
            if (domain.dnsSyncStatus === 'SYNC_FAILED') return '同步失败';
            return '未初始化';
        },

        getDnsSyncBadgeClass(domain) {
            if (!domain.dnsProviderConfigId) return 'bg-secondary-lt';
            if (domain.dnsSyncStatus === 'SYNCED') return 'bg-success-lt';
            if (domain.dnsSyncStatus === 'NOT_SYNCED') return 'bg-warning-lt';
            if (domain.dnsSyncStatus === 'PULLING' || domain.dnsSyncStatus === 'PUSHING') return 'bg-info-lt';
            if (domain.dnsSyncStatus === 'SYNC_FAILED') return 'bg-danger-lt';
            return 'bg-secondary-lt';
        },

        isDnsPulling(domainId) {
            return this.pullingDomainIds.includes(domainId);
        },

        isDnsPushing(domainId) {
            return this.pushingDomainIds.includes(domainId);
        },

        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

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
            const today = new Date();
            today.setFullYear(today.getFullYear() + 1);
            this.newItem = {
                domain: '',
                expireDate: today.toISOString().split('T')[0],
                price: '',
                remark: ''
            };
        },

        prepareEditForm(domain) {
            const formatDateForInput = (dateString) => {
                if (!dateString) return '';
                const date = new Date(dateString);
                return date.toISOString().split('T')[0];
            };

            return {
                id: domain.id,
                domain: domain.domain,
                expireDate: formatDateForInput(domain.expireDate),
                price: domain.price,
                remark: domain.remark || ''
            };
        },

        navigateToDnsRecords(domain) {
            window.location.href = `/console/device/dns-record?domainId=${domain.id}`;
        },

        openDnsBindingModal(domain) {
            this.selectedItem = domain;
            this.validationErrors = {};
            this.dnsBindingData = {
                dnsProviderConfigId: domain.dnsProviderConfigId ? String(domain.dnsProviderConfigId) : '',
                zoneId: domain.dnsZoneId || ''
            };
            this.dnsBindingModal = new Modal(document.getElementById('dnsBindingModal'));
            this.dnsBindingModal.show();
        },

        validateDnsBindingForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.dnsBindingData.dnsProviderConfigId) {
                this.validationErrors.dnsProviderConfigId = '请选择 DNS 服务商';
                isValid = false;
            }
            if (!this.dnsBindingData.zoneId || !this.dnsBindingData.zoneId.trim()) {
                this.validationErrors.zoneId = 'Zone ID 不能为空';
                isValid = false;
            }
            return isValid;
        },

        saveDnsBinding() {
            if (!this.selectedItem || !this.validateDnsBindingForm()) {
                return;
            }

            fetch(`/api/admin/domains/${this.selectedItem.id}/dns-provider`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    dnsProviderConfigId: Number(this.dnsBindingData.dnsProviderConfigId),
                    zoneId: this.dnsBindingData.zoneId.trim()
                })
            })
                .then(async response => {
                    const data = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        throw new Error(data.message || '保存 DNS 绑定失败');
                    }
                    return data;
                })
                .then(() => {
                    this.fetchRecords();
                    this.dnsBindingModal.hide();
                    ToastUtils.show('Success', 'DNS 服务商绑定已更新', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '保存 DNS 绑定失败', 'danger');
                });
        },

        unbindDnsProvider() {
            if (!this.selectedItem) {
                return;
            }

            if (!confirm('确定要解除当前域名的 DNS 服务商绑定吗？本地 DNS 记录快照也会被清空。')) {
                return;
            }

            fetch(`/api/admin/domains/${this.selectedItem.id}/dns-provider`, {
                method: 'DELETE'
            })
                .then(async response => {
                    const data = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        throw new Error(data.message || '解除绑定失败');
                    }
                    return data;
                })
                .then(() => {
                    this.fetchRecords();
                    this.dnsBindingModal.hide();
                    ToastUtils.show('Success', 'DNS 服务商绑定已解除', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '解除绑定失败', 'danger');
                });
        },

        pullDnsRecords(domain) {
            if (this.isDnsPulling(domain.id) || this.isDnsPushing(domain.id)) {
                return;
            }
            this.pullingDomainIds.push(domain.id);
            const loadingToast = ToastUtils.loading(
                '拉取中',
                `${domain.domain} 的 DNS 记录正在拉取，请稍候...`
            );
            fetch(`/api/admin/domains/${domain.id}/dns-records/pull`, {
                method: 'POST'
            })
                .then(async response => {
                    const data = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        throw new Error(data.message || '拉取 DNS 记录失败');
                    }
                    return data;
                })
                .then(data => {
                    this.fetchRecords();
                    ToastUtils.show('Success', data.message || `成功拉取 ${data.pulledRecordCount || 0} 条 DNS 记录`, 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '拉取 DNS 记录失败', 'danger');
                })
                .finally(() => {
                    loadingToast.hide();
                    this.pullingDomainIds = this.pullingDomainIds.filter(id => id !== domain.id);
                });
        },

        pushDnsRecords(domain) {
            if (this.isDnsPulling(domain.id) || this.isDnsPushing(domain.id)) {
                return;
            }
            this.pushingDomainIds.push(domain.id);
            const loadingToast = ToastUtils.loading(
                '推送中',
                `${domain.domain} 的 DNS 变更正在推送，请稍候...`
            );
            fetch(`/api/admin/domains/${domain.id}/dns-records/push`, {
                method: 'POST'
            })
                .then(async response => {
                    const data = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        throw new Error(data.message || '推送 DNS 记录失败');
                    }
                    return data;
                })
                .then(data => {
                    this.fetchRecords();
                    ToastUtils.show('Success', data.message || `成功推送 ${data.pushedRecordCount || 0} 条 DNS 记录`, 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '推送 DNS 记录失败', 'danger');
                })
                .finally(() => {
                    loadingToast.hide();
                    this.pushingDomainIds = this.pushingDomainIds.filter(id => id !== domain.id);
                });
        },

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

        getApiUrl() {
            return '/api/admin/domains';
        }
    }
});

domainTable.createApp('#app');
