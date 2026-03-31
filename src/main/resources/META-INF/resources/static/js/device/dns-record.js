import { DataTable } from '/static/js/common/data-table.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';

const domainId = new URLSearchParams(window.location.search).get('domainId');

const DEFAULT_SORT_BY = 'type';
const DEFAULT_SORT_ORDER = 'asc';

const dnsRecordTable = new DataTable({
    data: {
        entityName: 'dns-records',
        modalIdPrefix: 'dns-record-',
        domainId: domainId ? Number(domainId) : null,
        domainIdError: !domainId,
        domain: null,
        dnsRecordTypes: ['A', 'AAAA', 'CNAME', 'TXT', 'MX', 'SRV', 'NS'],
        filters: {
            type: ''
        },
        sortBy: DEFAULT_SORT_BY,
        sortOrder: DEFAULT_SORT_ORDER,
        pageSize: 15,
        selectedRecordIds: [],
        dnsRecordStats: {
            total: 0,
            synced: 0,
            pending: 0,
            proxied: 0
        },
        createModal: null,
        editModal: null,
        dnsRecordBatchCreateModal: null,
        dnsRecordBatchUpdateModal: null,
        deleteModal: null,
        dnsPushing: false,
        dnsBatchCreateText: '',
        dnsBatchUpdateForm: {
            ttl: '',
            priority: '',
            proxied: null,
            remark: ''
        },
        newItem: {
            type: 'A',
            name: '@',
            content: '',
            ttl: 300,
            proxied: false,
            priority: null,
            remark: ''
        },
        editedItem: {
            id: null,
            type: 'A',
            name: '@',
            content: '',
            ttl: 300,
            proxied: false,
            priority: null,
            remark: ''
        }
    },
    methods: {
        mounted() {
            if (this.domainIdError) {
                this.loading = false;
                return;
            }
            this.fetchRecords();
            this.fetchDomainSummary();
        },

        getApiUrl() {
            return `/api/admin/domains/${this.domainId}/dns-records`;
        },

        getCreateUrl() {
            return `/api/admin/domains/${this.domainId}/dns-records`;
        },

        getUpdateUrl(item) {
            return `/api/admin/domains/${this.domainId}/dns-records/${item.id}`;
        },

        afterFetch() {
            const currentIds = new Set(this.records.map(item => item.id));
            this.selectedRecordIds = this.selectedRecordIds.filter(id => currentIds.has(id));
            this.refreshDnsRecordStats();
        },

        fetchRecords() {
            if (this.domainIdError) {
                this.loading = false;
                return;
            }

            this.loading = true;
            const params = new URLSearchParams({
                page: this.currentPage,
                size: this.pageSize,
                sortBy: this.sortBy,
                sortOrder: this.sortOrder
            });

            if (this.searchQuery) {
                params.append('search', this.searchQuery);
            }

            Object.entries(this.filters).forEach(([key, value]) => {
                if (value !== '' && value !== null && value !== undefined) {
                    params.append(key, value);
                }
            });

            fetch(`${this.getApiUrl()}?${params.toString()}`)
                .then(async response => {
                    const data = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        throw new Error(data.message || '加载 DNS 记录失败');
                    }
                    return data;
                })
                .then(data => {
                    this.records = data.records || [];
                    this.totalItems = data.total || 0;
                    this.startIndex = (this.currentPage - 1) * this.pageSize + 1;
                    this.endIndex = Math.min(this.startIndex + this.pageSize - 1, this.totalItems);
                    this.totalPages = data.pages || 0;
                    this.currentPage = data.current || 1;
                    this.loading = false;

                    if (typeof this.afterFetch === 'function') {
                        this.afterFetch(data);
                    }
                })
                .catch(error => {
                    console.error('Error fetching dns-records:', error);
                    ToastUtils.show('Error', error.message || '加载 DNS 记录失败', 'danger');
                    this.records = [];
                    this.totalItems = 0;
                    this.totalPages = 0;
                    this.loading = false;
                    this.refreshDnsRecordStats();
                });
        },

        fetchDomainSummary() {
            fetch(`/api/admin/domains/${this.domainId}`)
                .then(async response => {
                    const data = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        throw new Error(data.message || '加载域名信息失败');
                    }
                    return data;
                })
                .then(data => {
                    this.domain = data;
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '加载域名信息失败', 'danger');
                });
        },

        refreshDnsRecordStats() {
            this.dnsRecordStats = {
                total: this.totalItems || 0,
                synced: this.records.filter(record => record.status === 'SYNCED').length,
                pending: this.records.filter(record => ['PENDING_CREATE', 'PENDING_UPDATE', 'PENDING_DELETE'].includes(record.status)).length,
                proxied: this.records.filter(record => record.proxied === true).length
            };
        },

        refreshDnsRecords() {
            this.fetchDomainSummary();
            this.fetchRecords();
        },

        pushDnsRecords() {
            if (this.dnsPushing) {
                return;
            }
            this.dnsPushing = true;
            const loadingToast = ToastUtils.loading(
                '推送中',
                `${this.domain?.domain || '当前域名'} 的 DNS 变更正在推送，请稍候...`
            );
            fetch(`/api/admin/domains/${this.domainId}/dns-records/push`, {
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
                    this.fetchDomainSummary();
                    this.fetchRecords();
                    ToastUtils.show('Success', data.message || `成功推送 ${data.pushedRecordCount || 0} 条 DNS 记录`, 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '推送 DNS 记录失败', 'danger');
                })
                .finally(() => {
                    loadingToast.hide();
                    this.dnsPushing = false;
                });
        },

        onDnsRecordFilterChange() {
            this.currentPage = 1;
            this.fetchRecords();
        },

        hasActiveFilters() {
            return this.activeFilterCount() > 0;
        },

        activeFilterCount() {
            return this.filters.type ? 1 : 0;
        },

        getActiveFilterTags() {
            if (!this.filters.type) {
                return [];
            }
            return [{
                key: 'type',
                label: '类型',
                value: this.filters.type
            }];
        },

        clearFilterTag(key) {
            if (key === 'type') {
                this.filters.type = '';
            }
            this.onDnsRecordFilterChange();
        },

        resetFilters() {
            this.filters = {
                type: ''
            };
            this.onDnsRecordFilterChange();
        },

        toggleDnsRecordSort(field) {
            if (this.sortBy === field) {
                this.sortOrder = this.sortOrder === 'asc' ? 'desc' : 'asc';
            } else {
                this.sortBy = field;
                this.sortOrder = 'asc';
            }
            this.fetchRecords();
        },

        getDnsRecordSortIcon(field) {
            if (this.sortBy !== field) {
                return 'ti ti-selector';
            }
            return this.sortOrder === 'asc' ? 'ti ti-sort-ascending' : 'ti ti-sort-descending';
        },

        formatDnsDateTime(value) {
            if (!value) {
                return '';
            }
            const date = new Date(value);
            if (Number.isNaN(date.getTime())) {
                return value;
            }
            return date.toLocaleString('zh-CN', {
                year: 'numeric',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit'
            });
        },

        isDnsRecordSelected(recordId) {
            return this.selectedRecordIds.includes(recordId);
        },

        toggleDnsRecordSelection(recordId, checked) {
            if (checked) {
                if (!this.selectedRecordIds.includes(recordId)) {
                    this.selectedRecordIds.push(recordId);
                }
                return;
            }
            this.selectedRecordIds = this.selectedRecordIds.filter(id => id !== recordId);
        },

        isAllDnsRecordsSelected() {
            return this.records.length > 0 && this.records.every(record => this.selectedRecordIds.includes(record.id));
        },

        toggleSelectAllDnsRecords(checked) {
            if (checked) {
                const merged = new Set([...this.selectedRecordIds, ...this.records.map(record => record.id)]);
                this.selectedRecordIds = Array.from(merged);
                return;
            }
            const currentIds = new Set(this.records.map(record => record.id));
            this.selectedRecordIds = this.selectedRecordIds.filter(id => !currentIds.has(id));
        },

        getDnsRecordStatusDescription(record) {
            if (record.status === 'SYNCED') return '已同步';
            if (record.status === 'PENDING_CREATE') return '待创建';
            if (record.status === 'PENDING_UPDATE') return '待更新';
            if (record.status === 'PENDING_DELETE') return '待删除';
            if (record.status === 'SYNC_FAILED') return '同步失败';
            return record.status || '-';
        },

        getDnsRecordStatusBadgeClass(record) {
            if (record.status === 'SYNCED') return 'bg-success-lt';
            if (record.status === 'PENDING_CREATE') return 'bg-primary-lt';
            if (record.status === 'PENDING_UPDATE') return 'bg-warning-lt';
            if (record.status === 'PENDING_DELETE') return 'bg-danger-lt';
            if (record.status === 'SYNC_FAILED') return 'bg-danger-lt';
            return 'bg-secondary-lt';
        },

        validateCreateForm() {
            return this.validateDnsRecordForm(this.newItem);
        },

        validateEditForm() {
            return this.validateDnsRecordForm(this.editedItem);
        },

        validateDnsRecordForm(form) {
            let isValid = true;
            this.validationErrors = {};

            if (!form.type) {
                this.validationErrors.type = '记录类型不能为空';
                isValid = false;
            }
            if (!form.name || !form.name.trim()) {
                this.validationErrors.name = '记录名称不能为空';
                isValid = false;
            }
            if (!form.content || !form.content.trim()) {
                this.validationErrors.content = '记录内容不能为空';
                isValid = false;
            }
            return isValid;
        },

        resetCreateForm() {
            this.newItem = {
                type: 'A',
                name: '@',
                content: '',
                ttl: 300,
                proxied: false,
                priority: null,
                remark: ''
            };
        },

        prepareEditForm(record) {
            return {
                id: record.id,
                type: record.type,
                name: record.name,
                content: record.content,
                ttl: record.ttl || '',
                proxied: record.proxied === true,
                priority: record.priority || '',
                remark: record.remark || ''
            };
        },

        prepareCreateData() {
            return this.buildDnsRecordPayload(this.newItem);
        },

        prepareUpdateData() {
            return this.buildDnsRecordPayload(this.editedItem);
        },

        buildDnsRecordPayload(form) {
            return {
                type: form.type,
                name: form.name.trim(),
                content: form.content.trim(),
                ttl: form.ttl === '' ? null : Number(form.ttl),
                proxied: form.proxied,
                priority: form.priority === '' ? null : Number(form.priority),
                remark: form.remark || null
            };
        },

        afterCreate() {
            this.fetchDomainSummary();
        },

        afterUpdate() {
            this.fetchDomainSummary();
        },

        openDnsRecordCreateModal() {
            this.openCreateModal();
        },

        openDnsRecordEditModal(record) {
            this.openEditModal(record);
        },

        createDnsRecord() {
            this.createItem();
        },

        updateDnsRecord() {
            this.updateItem();
        },

        deleteDnsRecord(record) {
            if (this.domainIdError || !record) {
                return;
            }
            this.selectedItem = record;
            this.deleteModal = new Modal(document.getElementById('dns-record-deleteModal'));
            this.deleteModal.show();
        },

        deleteDnsRecordConfirmed() {
            if (this.domainIdError || !this.selectedItem) {
                return;
            }
            fetch(`/api/admin/domains/${this.domainId}/dns-records/${this.selectedItem.id}`, {
                method: 'DELETE'
            })
                .then(async response => {
                    const data = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        throw new Error(data.message || '删除 DNS 记录失败');
                    }
                })
                .then(() => {
                    this.fetchDomainSummary();
                    this.fetchRecords();
                    if (this.deleteModal) {
                        this.deleteModal.hide();
                    }
                    this.selectedItem = null;
                    ToastUtils.show('Success', 'DNS 记录已删除', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '删除 DNS 记录失败', 'danger');
                });
        },

        openDnsRecordBatchCreateModal() {
            this.dnsBatchCreateText = '';
            this.dnsRecordBatchCreateModal = new Modal(document.getElementById('dns-record-batchCreateModal'));
            this.dnsRecordBatchCreateModal.show();
        },

        submitDnsBatchCreate() {
            const items = this.dnsBatchCreateText
                .split('\n')
                .map(line => line.trim())
                .filter(line => line)
                .map(line => {
                    const parts = line.split(',').map(part => part.trim());
                    return {
                        type: parts[0] || 'A',
                        name: parts[1] || '@',
                        content: parts[2] || '',
                        ttl: parts[3] ? Number(parts[3]) : null,
                        priority: parts[4] ? Number(parts[4]) : null,
                        proxied: parts[5] ? parts[5] === '1' : null,
                        remark: parts[6] || null
                    };
                });

            if (!items.length) {
                ToastUtils.show('Warning', '请先填写批量新增内容', 'warning');
                return;
            }

            this.submitDnsBatchRequest('CREATE', items, '批量新增 DNS 记录成功', () => this.dnsRecordBatchCreateModal.hide());
        },

        openDnsRecordBatchUpdateModal() {
            if (!this.selectedRecordIds.length) {
                ToastUtils.show('Warning', '请先勾选要修改的 DNS 记录', 'warning');
                return;
            }
            this.dnsBatchUpdateForm = {
                ttl: '',
                priority: '',
                proxied: null,
                remark: ''
            };
            this.dnsRecordBatchUpdateModal = new Modal(document.getElementById('dns-record-batchUpdateModal'));
            this.dnsRecordBatchUpdateModal.show();
        },

        submitDnsBatchUpdate() {
            const items = this.selectedRecordIds.map(id => ({
                id,
                ttl: this.dnsBatchUpdateForm.ttl === '' ? null : Number(this.dnsBatchUpdateForm.ttl),
                priority: this.dnsBatchUpdateForm.priority === '' ? null : Number(this.dnsBatchUpdateForm.priority),
                proxied: this.dnsBatchUpdateForm.proxied,
                remark: this.dnsBatchUpdateForm.remark || null
            }));

            if (!items.length) {
                ToastUtils.show('Warning', '请先勾选要修改的 DNS 记录', 'warning');
                return;
            }

            this.submitDnsBatchRequest('UPDATE', items, '批量修改 DNS 记录成功', () => this.dnsRecordBatchUpdateModal.hide());
        },

        batchDeleteDnsRecords() {
            if (!this.selectedRecordIds.length) {
                ToastUtils.show('Warning', '请先勾选要删除的 DNS 记录', 'warning');
                return;
            }
            if (!confirm(`确定删除已选中的 ${this.selectedRecordIds.length} 条 DNS 记录吗？`)) {
                return;
            }

            const items = this.selectedRecordIds.map(id => ({ id }));
            this.submitDnsBatchRequest('DELETE', items, '批量删除 DNS 记录成功');
        },

        submitDnsBatchRequest(action, items, successMessage, onSuccess = null) {
            fetch(`/api/admin/domains/${this.domainId}/dns-records/batch`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ action, items })
            })
                .then(async response => {
                    const data = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        throw new Error(data.message || '批量操作失败');
                    }
                    return data;
                })
                .then(() => {
                    this.selectedRecordIds = [];
                    this.fetchDomainSummary();
                    this.fetchRecords();
                    if (onSuccess) {
                        onSuccess();
                    }
                    ToastUtils.show('Success', successMessage, 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '批量操作失败', 'danger');
                });
        }
    }
});

dnsRecordTable.createApp('#app');
