import { DataTable } from '/static/js/common/data-table.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';
import { createAccountSearch, initSelectOnModalShow } from '/static/js/common/tom-select-helper.js';

const DEFAULT_SORT_BY = 'totalBytes';
const DEFAULT_SORT_DIRECTION = 'desc';
const getTodayDate = () => new Date().toISOString().slice(0, 10);
const createDefaultTrafficFilters = () => ({
    startDate: getTodayDate(),
    endDate: ''
});

const trafficStatsTable = new DataTable({
    data: {
        entityName: 'traffic-stats',
        modalIdPrefix: 'traffic-stats-',
        filters: createDefaultTrafficFilters(),
        createAccountSearch: null,
        editAccountSearch: null,
        totalUpload: 0,
        totalDownload: 0,
        totalUploadFormatted: '0 B',
        totalDownloadFormatted: '0 B',
        sortBy: DEFAULT_SORT_BY,
        sortDirection: DEFAULT_SORT_DIRECTION,
        newItem: {
            userId: '',
            accountId: '',
            periodStart: '',
            periodEnd: '',
            uploadBytes: 0,
            downloadBytes: 0,
            bandwidthQuota: null
        }
    },
    methods: {
        initialize() {
            this.initializeSearchComponents();
        },

        initializeSearchComponents() {
            initSelectOnModalShow(
                'traffic-stats-createModal',
                'account-search-create',
                createAccountSearch((value, option) => {
                    this.newItem.accountId = value || '';
                    this.newItem.userId = option?.userId || '';
                }),
                this,
                'createAccountSearch'
            );

            initSelectOnModalShow(
                'traffic-stats-editModal',
                'account-search-edit',
                createAccountSearch((value, option) => {
                    this.editedItem.accountId = value || '';
                    this.editedItem.userId = option?.userId || '';
                }),
                this,
                'editAccountSearch',
                (instance) => {
                    if (this.editedItem && this.editedItem.accountId) {
                        instance.addOption({
                            id: this.editedItem.accountId,
                            remark: this.editedItem.nickname || '未命名账户',
                            displayName: this.editedItem.nickname || '未命名账户',
                            userId: this.editedItem.userId
                        });
                        instance.setValue(this.editedItem.accountId, true);
                    }
                }
            );
        },

        fetchRecords() {
            this.loading = true;

            const params = new URLSearchParams({
                page: this.currentPage,
                size: this.pageSize,
                sortBy: this.sortBy,
                sortDirection: this.sortDirection
            });

            if (this.searchQuery) {
                params.append('search', this.searchQuery);
            }

            if (this.filters.startDate) {
                params.append('startDate', `${this.filters.startDate}T00:00:00`);
            }

            if (this.filters.endDate) {
                params.append('endDate', `${this.filters.endDate}T23:59:59`);
            }

            fetch(`/api/admin/traffic-stats?${params.toString()}`)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('Network response was not ok');
                    }
                    return response.json();
                })
                .then(data => {
                    this.records = data.records || [];
                    this.totalItems = data.total || 0;
                    this.startIndex = this.totalItems === 0 ? 0 : (this.currentPage - 1) * this.pageSize + 1;
                    this.endIndex = this.totalItems === 0 ? 0 : Math.min(this.startIndex + this.pageSize - 1, this.totalItems);
                    this.totalPages = data.pages || 0;
                    this.currentPage = data.current || 1;
                    this.loading = false;
                })
                .catch(error => {
                    console.error('Error fetching traffic stats:', error);
                    ToastUtils.show('Error', 'Failed to load traffic stats.', 'danger');
                    this.loading = false;
                });
        },

        getStatsUrl() {
            const params = new URLSearchParams();

            if (this.searchQuery) {
                params.append('search', this.searchQuery);
            }

            if (this.filters.startDate) {
                params.append('startDate', `${this.filters.startDate}T00:00:00`);
            }

            if (this.filters.endDate) {
                params.append('endDate', `${this.filters.endDate}T23:59:59`);
            }

            const queryString = params.toString();
            return queryString ? `/api/admin/traffic-stats/stats?${queryString}` : '/api/admin/traffic-stats/stats';
        },

        applyStatsData(data) {
            this.totalUpload = data.totalUpload || 0;
            this.totalDownload = data.totalDownload || 0;
            this.totalUploadFormatted = data.totalUploadFormatted || '0 B';
            this.totalDownloadFormatted = data.totalDownloadFormatted || '0 B';
        },

        searchDebounced() {
            if (this.searchTimeout) {
                clearTimeout(this.searchTimeout);
            }

            this.searchTimeout = setTimeout(() => {
                this.currentPage = 1;
                this.fetchRecords();
                this.refreshStats();
            }, 500);
        },

        toggleSort(field) {
            if (this.sortBy === field) {
                this.sortDirection = this.sortDirection === 'desc' ? 'asc' : 'desc';
            } else {
                this.sortBy = field;
                this.sortDirection = 'desc';
            }
            this.currentPage = 1;
            this.fetchRecords();
        },

        isSortActive(field) {
            return this.sortBy === field;
        },

        getSortIcon(field) {
            if (this.sortBy !== field) {
                return 'ti ti-selector';
            }
            return this.sortDirection === 'desc' ? 'ti ti-sort-descending' : 'ti ti-sort-ascending';
        },

        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.newItem.accountId) {
                this.validationErrors.accountId = '请选择账户';
                isValid = false;
            }

            if (!this.newItem.periodStart) {
                this.validationErrors.periodStart = '开始时间不能为空';
                isValid = false;
            }

            if (!this.newItem.periodEnd) {
                this.validationErrors.periodEnd = '结束时间不能为空';
                isValid = false;
            } else if (this.newItem.periodStart && new Date(this.newItem.periodEnd) < new Date(this.newItem.periodStart)) {
                this.validationErrors.periodEnd = '结束时间必须晚于开始时间';
                isValid = false;
            }

            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.editedItem.accountId) {
                this.validationErrors.accountId = '请选择账户';
                isValid = false;
            }

            if (!this.editedItem.periodStart) {
                this.validationErrors.periodStart = '开始时间不能为空';
                isValid = false;
            }

            if (!this.editedItem.periodEnd) {
                this.validationErrors.periodEnd = '结束时间不能为空';
                isValid = false;
            } else if (this.editedItem.periodStart && new Date(this.editedItem.periodEnd) < new Date(this.editedItem.periodStart)) {
                this.validationErrors.periodEnd = '结束时间必须晚于开始时间';
                isValid = false;
            }

            return isValid;
        },

        prepareCreateData() {
            const quota = this.newItem.bandwidthQuota === '' ? null
                : (this.newItem.bandwidthQuota != null ? Number(this.newItem.bandwidthQuota) : null);
            return {
                userId: this.newItem.userId,
                accountId: this.newItem.accountId,
                periodStart: this.newItem.periodStart,
                periodEnd: this.newItem.periodEnd,
                uploadBytes: this.newItem.uploadBytes || 0,
                downloadBytes: this.newItem.downloadBytes || 0,
                bandwidthQuota: quota
            };
        },

        prepareUpdateData() {
            return {
                userId: this.editedItem.userId,
                accountId: this.editedItem.accountId,
                periodStart: this.editedItem.periodStart,
                periodEnd: this.editedItem.periodEnd,
                uploadBytes: this.editedItem.uploadBytes || 0,
                downloadBytes: this.editedItem.downloadBytes || 0
            };
        },

        updateItem() {
            if (typeof this.validateEditForm === 'function' && !this.validateEditForm()) {
                return;
            }

            const id = this.editedItem.id;
            const quota = this.editedItem.bandwidthQuota === '' ? null
                : (this.editedItem.bandwidthQuota != null ? Number(this.editedItem.bandwidthQuota) : null);
            const putData = this.prepareUpdateData();

            Promise.all([
                fetch(`/api/admin/traffic-stats/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(putData)
                }).then(r => { if (!r.ok) throw new Error('更新失败'); return r.json(); }),
                fetch(`/api/admin/traffic-stats/${id}/quota`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ bandwidthQuota: quota })
                }).then(r => { if (!r.ok) throw new Error('配额更新失败'); })
            ]).then(([updatedRecord]) => {
                const index = this.records.findIndex(r => r.id === id);
                if (index !== -1) {
                    this.records.splice(index, 1, {
                        ...this.records[index],
                        ...updatedRecord,
                        bandwidthQuota: quota
                    });
                }
                this.editModal.hide();
                ToastUtils.show('Success', '更新成功', 'success');
            }).catch(error => {
                console.error('Error:', error);
                ToastUtils.show('Error', error.message || '更新失败', 'danger');
            });
        },

        resetCreateForm() {
            const now = new Date();
            const localDateTimeFormat = now.toISOString().slice(0, 16);

            this.newItem = {
                userId: '',
                accountId: '',
                periodStart: localDateTimeFormat,
                periodEnd: localDateTimeFormat,
                uploadBytes: 0,
                downloadBytes: 0,
                bandwidthQuota: null
            };

            this.createAccountSearch?.clear();
        },

        prepareEditForm(record) {
            const formatDateForInput = (dateString) => {
                if (!dateString) {
                    return '';
                }
                return new Date(dateString).toISOString().slice(0, 16);
            };

            return {
                id: record.id,
                userId: record.userId,
                accountId: record.accountId,
                nickname: record.nickname,
                periodStart: formatDateForInput(record.periodStart),
                periodEnd: formatDateForInput(record.periodEnd),
                uploadBytes: record.uploadBytes || 0,
                downloadBytes: record.downloadBytes || 0,
                bandwidthQuota: record.bandwidthQuota != null ? record.bandwidthQuota : null
            };
        },

        getApiUrl() {
            return '/api/admin/traffic-stats';
        },

...createResponsiveFilterMethods({
            createDefaultFilters: () => createDefaultTrafficFilters(),
            applyFilters() {
                this.currentPage = 1;
                this.fetchRecords();
                this.refreshStats();
            },
            onReset() {
                this.sortBy = DEFAULT_SORT_BY;
                this.sortDirection = DEFAULT_SORT_DIRECTION;
            },
            getActiveTags() {
                const tags = this.searchQuery ? [{
                    key: 'search',
                    label: '搜索',
                    value: this.searchQuery
                }] : [];
                const defaults = this.createDefaultFilters();

                if (this.filters.startDate !== defaults.startDate) {
                    tags.push({ key: 'startDate', label: '开始时间', value: this.filters.startDate || '未设置' });
                }

                if (this.filters.endDate !== defaults.endDate) {
                    tags.push({ key: 'endDate', label: '结束时间', value: this.filters.endDate || '未设置' });
                }

                return tags;
            }
        })
    }
});

trafficStatsTable.createApp('#app');
