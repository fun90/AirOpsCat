import { DataTable } from '/static/js/common/data-table.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';

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
            downloadBytes: 0
        }
    },
    methods: {
        initialize() {
            this.initializeSearchComponents();
        },

        initializeSearchComponents() {
            setTimeout(() => {
                const createSelectElement = document.getElementById('account-search-create');
                if (createSelectElement) {
                    this.createAccountSearch = new TomSelect(createSelectElement, {
                        valueField: 'id',
                        labelField: 'displayName',
                        searchField: ['remark', 'accountNo'],
                        placeholder: '搜索账户备注或账号...',
                        render: {
                            option: (data, escape) => {
                                return `<div>${escape(data.remark || '未命名账户')}${data.accountNo ? ` (${escape(data.accountNo)})` : ''}</div>`;
                            },
                            item: (data, escape) => {
                                return `<div>${escape(data.remark || '未命名账户')}${data.accountNo ? ` (${escape(data.accountNo)})` : ''}</div>`;
                            }
                        },
                        load: (query, callback) => {
                            if (!query.length || query.length < 2) {
                                callback();
                                return;
                            }
                            fetch(`/api/admin/accounts?search=${encodeURIComponent(query)}&size=20`)
                                .then(response => response.json())
                                .then(data => {
                                    const accounts = (data.records || data).map(item => ({
                                        ...item,
                                        displayName: `${item.remark || '未命名账户'}${item.accountNo ? ` (${item.accountNo})` : ''}`
                                    }));
                                    callback(accounts);
                                })
                                .catch(() => callback());
                        },
                        onChange: (value) => {
                            if (value) {
                                const option = this.createAccountSearch.options[value];
                                this.newItem.accountId = value;
                                this.newItem.userId = option?.userId || '';
                            } else {
                                this.newItem.accountId = '';
                                this.newItem.userId = '';
                            }
                        }
                    });
                    createSelectElement.style.display = 'none';
                }

                const editSelectElement = document.getElementById('account-search-edit');
                if (editSelectElement) {
                    this.editAccountSearch = new TomSelect(editSelectElement, {
                        valueField: 'id',
                        labelField: 'displayName',
                        searchField: ['remark', 'accountNo'],
                        placeholder: '搜索账户备注或账号...',
                        render: {
                            option: (data, escape) => {
                                return `<div>${escape(data.remark || '未命名账户')}${data.accountNo ? ` (${escape(data.accountNo)})` : ''}</div>`;
                            },
                            item: (data, escape) => {
                                return `<div>${escape(data.remark || '未命名账户')}${data.accountNo ? ` (${escape(data.accountNo)})` : ''}</div>`;
                            }
                        },
                        load: (query, callback) => {
                            if (!query.length || query.length < 2) {
                                callback();
                                return;
                            }
                            fetch(`/api/admin/accounts?search=${encodeURIComponent(query)}&size=20`)
                                .then(response => response.json())
                                .then(data => {
                                    const accounts = (data.records || data).map(item => ({
                                        ...item,
                                        displayName: `${item.remark || '未命名账户'}${item.accountNo ? ` (${item.accountNo})` : ''}`
                                    }));
                                    callback(accounts);
                                })
                                .catch(() => callback());
                        },
                        onChange: (value) => {
                            if (value) {
                                const option = this.editAccountSearch.options[value];
                                this.editedItem.accountId = value;
                                this.editedItem.userId = option?.userId || '';
                            } else {
                                this.editedItem.accountId = '';
                                this.editedItem.userId = '';
                            }
                        }
                    });
                    editSelectElement.style.display = 'none';
                }
            }, 100);
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
                    this.totalUpload = data.totalUpload || 0;
                    this.totalDownload = data.totalDownload || 0;
                    this.totalUploadFormatted = data.totalUploadFormatted || '0 B';
                    this.totalDownloadFormatted = data.totalDownloadFormatted || '0 B';
                    this.loading = false;
                })
                .catch(error => {
                    console.error('Error fetching traffic stats:', error);
                    ToastUtils.show('Error', 'Failed to load traffic stats.', 'danger');
                    this.loading = false;
                });
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
            return {
                userId: this.newItem.userId,
                accountId: this.newItem.accountId,
                periodStart: this.newItem.periodStart,
                periodEnd: this.newItem.periodEnd,
                uploadBytes: this.newItem.uploadBytes || 0,
                downloadBytes: this.newItem.downloadBytes || 0
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

        resetCreateForm() {
            const now = new Date();
            const localDateTimeFormat = now.toISOString().slice(0, 16);

            this.newItem = {
                userId: '',
                accountId: '',
                periodStart: localDateTimeFormat,
                periodEnd: localDateTimeFormat,
                uploadBytes: 0,
                downloadBytes: 0
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

            const editedItem = {
                id: record.id,
                userId: record.userId,
                accountId: record.accountId,
                periodStart: formatDateForInput(record.periodStart),
                periodEnd: formatDateForInput(record.periodEnd),
                uploadBytes: record.uploadBytes || 0,
                downloadBytes: record.downloadBytes || 0
            };

            if (this.editAccountSearch) {
                this.editAccountSearch.clearOptions();
                this.editAccountSearch.addOption({
                    id: record.accountId,
                    remark: record.nickname || '未命名账户',
                    userId: record.userId
                });
                this.editAccountSearch.setValue(record.accountId, true);
            }

            return editedItem;
        },

        getApiUrl() {
            return '/api/admin/traffic-stats';
        },

        ...createResponsiveFilterMethods({
            createDefaultFilters: () => createDefaultTrafficFilters(),
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
