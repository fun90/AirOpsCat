import { DataTable } from '/static/js/common/data-table.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';

const dnsProviderTable = new DataTable({
    data: {
        entityName: 'dns-provider-configs',
        modalIdPrefix: 'dns-provider-',
        filters: {
            providerType: '',
            status: ''
        },
        stats: {
            total: 0,
            enabled: 0,
            disabled: 0,
            cloudflare: 0
        },
        boundDomains: [],
        boundDomainsModal: null,
        newItem: {
            displayName: '',
            providerType: 'CLOUDFLARE',
            apiToken: '',
            accountId: '',
            remark: '',
            enabled: true
        }
    },
    methods: {
        getStatusDescription(provider) {
            return provider.status === 'ENABLED' ? '启用' : '禁用';
        },

        getStatusBadgeClass(provider) {
            return provider.status === 'ENABLED' ? 'bg-success-lt' : 'bg-danger-lt';
        },

        getCheckDescription(provider) {
            if (provider.lastCheckStatus === 'SUCCESS') {
                return '检测通过';
            }
            if (provider.lastCheckStatus === 'FAILED') {
                return '检测失败';
            }
            return '未检测';
        },

        getCheckBadgeClass(provider) {
            if (provider.lastCheckStatus === 'SUCCESS') {
                return 'bg-success-lt';
            }
            if (provider.lastCheckStatus === 'FAILED') {
                return 'bg-danger-lt';
            }
            return 'bg-secondary-lt';
        },

        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.newItem.displayName || !this.newItem.displayName.trim()) {
                this.validationErrors.displayName = '显示名称不能为空';
                isValid = false;
            }
            if (!this.newItem.apiToken || !this.newItem.apiToken.trim()) {
                this.validationErrors.apiToken = 'API Token 不能为空';
                isValid = false;
            }
            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.editedItem.displayName || !this.editedItem.displayName.trim()) {
                this.validationErrors.displayName = '显示名称不能为空';
                isValid = false;
            }
            return isValid;
        },

        prepareCreateData() {
            return {
                displayName: this.newItem.displayName.trim(),
                providerType: this.newItem.providerType,
                apiToken: this.newItem.apiToken.trim(),
                accountId: this.newItem.accountId || null,
                remark: this.newItem.remark || null,
                status: this.newItem.enabled ? 'ENABLED' : 'DISABLED'
            };
        },

        prepareUpdateData() {
            return {
                displayName: this.editedItem.displayName.trim(),
                providerType: this.editedItem.providerType,
                apiToken: this.editedItem.apiToken ? this.editedItem.apiToken.trim() : null,
                accountId: this.editedItem.accountId || null,
                remark: this.editedItem.remark || null,
                status: this.editedItem.status
            };
        },

        resetCreateForm() {
            this.newItem = {
                displayName: '',
                providerType: 'CLOUDFLARE',
                apiToken: '',
                accountId: '',
                remark: '',
                enabled: true
            };
        },

        prepareEditForm(provider) {
            return {
                id: provider.id,
                displayName: provider.displayName,
                providerType: provider.providerType,
                apiToken: '',
                apiTokenMasked: provider.apiTokenMasked,
                accountId: provider.accountId || '',
                remark: provider.remark || '',
                status: provider.status
            };
        },

        getApiUrl() {
            return '/api/admin/dns-provider-configs';
        },

        getStatsUrl() {
            return '/api/admin/dns-provider-configs/stats';
        },

        getToggleStatusUrl(item, action) {
            return `/api/admin/dns-provider-configs/${item.id}/${action}`;
        },

        updateItemStatus(item, data) {
            const index = this.records.findIndex(record => record.id === item.id);
            if (index !== -1) {
                this.records[index].status = data.status;
            }
        },

        testConnection(provider) {
            fetch(`/api/admin/dns-provider-configs/${provider.id}/test`, {
                method: 'POST'
            })
                .then(async response => {
                    const data = await response.json();
                    if (!response.ok) {
                        throw new Error(data.message || '测试连接失败');
                    }
                    return data;
                })
                .then(data => {
                    this.fetchRecords();
                    ToastUtils.show(data.success ? 'Success' : 'Warning', data.message, data.success ? 'success' : 'warning');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '测试连接失败', 'danger');
                });
        },

        openBoundDomainsModal(provider) {
            this.selectedItem = provider;
            this.boundDomains = [];
            fetch(`/api/admin/dns-provider-configs/${provider.id}/domains`)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('加载绑定域名失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.boundDomains = data || [];
                    this.boundDomainsModal = new Modal(document.getElementById('dns-provider-boundDomainsModal'));
                    this.boundDomainsModal.show();
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '加载绑定域名失败', 'danger');
                });
        }
    }
});

dnsProviderTable.createApp('#app');
