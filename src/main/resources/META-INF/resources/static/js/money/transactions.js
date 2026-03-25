
import { DataTable } from '/static/js/common/data-table.js';
import { formatDateTimeForLocal } from '/static/js/common/common.js';
import ApexCharts from '/static/js/apexcharts.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';

const DEFAULT_TRANSACTION_FILTERS = Object.freeze({
    type: '',
    businessTable: ''
});

function getCurrentThemeMode() {
    return (
        document.documentElement.getAttribute('data-bs-theme') ||
        window.localStorage.getItem('tabler-theme') ||
        'light'
    );
}

function getApexThemeOptions(mode) {
    const isDark = mode === 'dark';
    const axisLabelColor = isDark ? '#d0d4db' : '#7e7e8d';
    const gridColor = isDark ? '#d0d4db' : '#a8afaf';

    return {
        theme: { mode },
        tooltip: { theme: mode },
        xaxis: { labels: { style: { colors: axisLabelColor } } },
        yaxis: { labels: { style: { colors: axisLabelColor } } },
        legend: { labels: { colors: axisLabelColor } },
        grid: { borderColor: gridColor }
    };
}

function formatYAxisCurrency(value) {
    return '¥' + value.toFixed(2);
}

const transactionTable = new DataTable({
    data: {
        entityName: 'transactions',
        modalIdPrefix: 'transaction-',
        filters: { ...DEFAULT_TRANSACTION_FILTERS },
        transactionTypes: [],
        paymentMethods: [],
        businessTables: [],
        
        // 搜索组件实例
        businessSearch: null,
        editBusinessSearch: null,
        trendsChart: null,
        stats: {
            totalIncome: 0,
            totalExpense: 0,
            netBalance: 0,
            currentMonthIncome: 0,
            currentMonthExpense: 0
        },
        monthlyStats: [],
        newItem: {
            transactionDate: '',
            amount: '',
            type: 0, // Default to income
            businessTable: '',
            businessId: '',
            description: '',
            paymentMethod: '',
            remark: ''
        }
    },
    methods: {
        // Initialize data when the component is mounted
        initialize() {
            this.fetchTransactionTypes();
            this.fetchPaymentMethods();
            this.fetchBusinessTables();
            this.fetchMonthlyStats();
            this.initThemeObserver();

            // Set default transactionDate for new transaction
            const now = new Date();
            this.newItem.transactionDate = formatDateTimeForLocal(now);

            // Initialize search components
            this.initializeSearchComponents();
        },

        // Initialize search dropdown components
        initializeSearchComponents() {
            setTimeout(() => {
                const createSelectElement = document.getElementById('business-search');
                if (createSelectElement) {
                    this.businessSearch = new TomSelect(createSelectElement, {
                        placeholder: '请先选择业务类型',
                        onChange: (value) => {
                            if (value) {
                                const option = this.businessSearch.options[value];
                                this.newItem.businessId = value;
                                this.newItem.remark = option?.remark || option?.name || '';
                            } else {
                                this.newItem.businessId = '';
                                this.newItem.remark = '';
                            }
                        }
                    });
                    this.businessSearch.disable();
                }

                const editSelectElement = document.getElementById('edit-business-search');
                if (editSelectElement) {
                    this.editBusinessSearch = new TomSelect(editSelectElement, {
                        placeholder: '请先选择业务类型',
                        onChange: (value) => {
                            if (value) {
                                const option = this.editBusinessSearch.options[value];
                                this.editedItem.businessId = value;
                                this.editedItem.remark = option?.remark || option?.name || '';
                            } else {
                                this.editedItem.businessId = '';
                                this.editedItem.remark = '';
                            }
                        }
                    });
                    this.editBusinessSearch.disable();
                }
            }, 100);
        },



        // After fetch hook
        afterFetch(data) {
            // Additional processing after data fetch if needed
        },

        // Format currency
        formatCurrency(value) {
            if (value === null || value === undefined) return "0.00";
            return parseFloat(value).toFixed(2);
        },

        // Get Badge classes
        getTypeBadgeClass(type) {
            return type == 0 ? 'text-bg-success' : 'text-bg-danger';
        },

        getBusinessTableLabel(table) {
            const found = this.businessTables.find(t => t.value === table);
            return found ? found.label : table;
        },

        // Load transaction types
        fetchTransactionTypes() {
            fetch('/api/admin/transactions/types')
                .then(response => response.json())
                .then(data => {
                    this.transactionTypes = data;
                })
                .catch(error => {
                    console.error('Error fetching transaction types:', error);
                });
        },

        // Load paymentMethods
        fetchPaymentMethods() {
            fetch('/api/admin/transactions/paymentMethods')
                .then(response => response.json())
                .then(data => {
                    this.paymentMethods = data;
                })
                .catch(error => {
                    console.error('Error fetching paymentMethods:', error);
                });
        },

        // Load business tables
        fetchBusinessTables() {
            fetch('/api/admin/transactions/business-tables')
                .then(response => response.json())
                .then(data => {
                    this.businessTables = data;
                })
                .catch(error => {
                    console.error('Error fetching business tables:', error);
                });
        },



        // Fetch monthly stats and initialize chart
        fetchMonthlyStats() {
            fetch('/api/admin/transactions/monthly-stats?months=6')
                .then(response => response.json())
                .then(data => {
                    this.monthlyStats = data;
                    this.initTrendsChart();
                })
                .catch(error => {
                    console.error('Error fetching monthly stats:', error);
                });
        },

        // Initialize trends chart
        initTrendsChart() {
            if (this.monthlyStats.length === 0) return;

            if (this.trendsChart) {
                this.trendsChart.destroy();
            }

            const months = this.monthlyStats.map(stat => stat.month);
            const incomeData = this.monthlyStats.map(stat => parseFloat(stat.income));
            const expenseData = this.monthlyStats.map(stat => parseFloat(stat.expense));
            const balanceData = this.monthlyStats.map(stat => parseFloat(stat.balance));

            const themeOptions = getApexThemeOptions(getCurrentThemeMode());
            const options = {
                chart: {
                    type: 'line',
                    height: 350,
                    toolbar: {
                        show: false
                    },
                    background: 'transparent'
                },
                theme: themeOptions.theme,
                series: [
                    {
                        name: '收入',
                        type: 'column',
                        data: incomeData
                    },
                    {
                        name: '支出',
                        type: 'column',
                        data: expenseData
                    },
                    {
                        name: '余额',
                        type: 'line',
                        data: balanceData
                    }
                ],
                xaxis: {
                    categories: months,
                    labels: {
                        style: {
                            colors: themeOptions.xaxis.labels.style.colors
                        }
                    }
                },
                yaxis: {
                    labels: {
                        style: {
                            colors: themeOptions.yaxis.labels.style.colors
                        },
                        formatter: formatYAxisCurrency
                    }
                },
                colors: ['#28a745', '#dc3545', '#ffc107'],
                stroke: {
                    width: [0, 0, 3],
                    curve: 'smooth'
                },
                fill: {
                    opacity: [0.8, 0.8, 1],
                    type: ['solid', 'solid', 'solid']
                },
                plotOptions: {
                    bar: {
                        columnWidth: '50%'
                    }
                },
                dataLabels: {
                    enabled: false
                },
                legend: {
                    position: 'top',
                    horizontalAlign: 'left',
                    labels: {
                        colors: themeOptions.legend.labels.colors
                    }
                },
                grid: {
                    borderColor: themeOptions.grid.borderColor,
                    strokeDashArray: 5
                },
                tooltip: {
                    theme: themeOptions.tooltip.theme,
                    shared: true,
                    intersect: false,
                    y: {
                        formatter: formatYAxisCurrency
                    }
                }
            };

            this.trendsChart = new ApexCharts(document.querySelector('#trends-chart'), options);
            this.trendsChart.render();
        },

        initThemeObserver() {
            const applyTheme = () => {
                if (!this.trendsChart) return;
                const themeOptions = getApexThemeOptions(getCurrentThemeMode());
                const chartConfig = this.trendsChart.w && this.trendsChart.w.config;
                const yaxisConfig = Array.isArray(chartConfig.yaxis) ? chartConfig.yaxis[0] : chartConfig.yaxis;
                const xaxisLabels = chartConfig.xaxis && chartConfig.xaxis.labels ? chartConfig.xaxis.labels : {};
                const yaxisLabels = yaxisConfig && yaxisConfig.labels ? yaxisConfig.labels : {};

                this.trendsChart.updateOptions(
                    {
                        theme: themeOptions.theme,
                        tooltip: themeOptions.tooltip,
                        xaxis: {
                            labels: {
                                ...xaxisLabels,
                                style: { colors: themeOptions.xaxis.labels.style.colors }
                            }
                        },
                        yaxis: {
                            labels: {
                                ...yaxisLabels,
                                style: { colors: themeOptions.yaxis.labels.style.colors },
                                formatter: yaxisLabels.formatter || formatYAxisCurrency
                            }
                        },
                        legend: themeOptions.legend,
                        grid: themeOptions.grid
                    },
                    false,
                    true
                );
            };

            const observer = new MutationObserver(() => {
                applyTheme();
            });

            observer.observe(document.documentElement, {
                attributes: true,
                attributeFilter: ['data-bs-theme']
            });
        },

        // Get Tom Select configuration for business type
        getBusinessSearchConfig(businessTable) {
            switch (businessTable) {
                case 'account':
                    return {
                        valueField: 'id',
                        labelField: 'remark',
                        searchField: ['remark', 'accountNo'],
                        placeholder: '搜索账户备注或账号...',
                        disabledField: null,
                        load: (query, callback) => {
                            if (!query.length || query.length < 2) {
                                callback();
                                return;
                            }
                            fetch(`/api/admin/accounts?search=${encodeURIComponent(query)}&size=20`)
                                .then(response => response.json())
                                .then(data => callback(data.records || data))
                                .catch(() => callback());
                        }
                    };
                case 'domain':
                    return {
                        valueField: 'id',
                        labelField: 'domain',
                        searchField: ['domain'],
                        placeholder: '搜索域名...',
                        disabledField: null,
                        load: (query, callback) => {
                            if (!query.length || query.length < 2) {
                                callback();
                                return;
                            }
                            fetch(`/api/admin/domains?search=${encodeURIComponent(query)}&size=20`)
                                .then(response => response.json())
                                .then(data => callback(data.records || data))
                                .catch(() => callback());
                        }
                    };
                case 'server':
                    return {
                        valueField: 'id',
                        labelField: 'name',
                        searchField: ['name', 'ip'],
                        placeholder: '搜索服务器名称或IP...',
                        disabledField: null,
                        load: (query, callback) => {
                            if (!query.length || query.length < 2) {
                                callback();
                                return;
                            }
                            fetch(`/api/admin/servers?search=${encodeURIComponent(query)}&size=20`)
                                .then(response => response.json())
                                .then(data => callback(data.records || data))
                                .catch(() => callback());
                        }
                    };
                default:
                    return null;
            }
        },

        // Handle business table change event
        onBusinessTableChange() {
            this.newItem.businessId = '';

            if (this.businessSearch) {
                this.businessSearch.destroy();
            }

            setTimeout(() => {
                const createSelectElement = document.getElementById('business-search');
                if (!createSelectElement) return;

                createSelectElement.disabled = false;

                if (this.newItem.businessTable) {
                    const config = this.getBusinessSearchConfig(this.newItem.businessTable);
                    if (config) {
                        this.businessSearch = new TomSelect(createSelectElement, {
                            ...config,
                            onChange: (value) => {
                                if (value) {
                                    const option = this.businessSearch.options[value];
                                    this.newItem.businessId = value;
                                    this.newItem.remark = option?.remark || option?.name || '';
                                } else {
                                    this.newItem.businessId = '';
                                    this.newItem.remark = '';
                                }
                            }
                        });
                    }
                } else {
                    this.businessSearch = new TomSelect(createSelectElement, {
                        placeholder: '请先选择业务类型'
                    });
                    this.businessSearch.disable();
                }
            }, 50);
        },

        // Handle edit business table change event
        onEditBusinessTableChange() {
            this.editedItem.businessId = '';

            if (this.editBusinessSearch) {
                this.editBusinessSearch.destroy();
            }

            setTimeout(() => {
                const editSelectElement = document.getElementById('edit-business-search');
                if (!editSelectElement) return;

                editSelectElement.disabled = false;

                if (this.editedItem.businessTable) {
                    const config = this.getBusinessSearchConfig(this.editedItem.businessTable);
                    if (config) {
                        this.editBusinessSearch = new TomSelect(editSelectElement, {
                            ...config,
                            onChange: (value) => {
                                if (value) {
                                    const option = this.editBusinessSearch.options[value];
                                    this.editedItem.businessId = value;
                                    this.editedItem.remark = option?.remark || option?.name || '';
                                } else {
                                    this.editedItem.businessId = '';
                                    this.editedItem.remark = '';
                                }
                            }
                        });
                    }
                } else {
                    this.editBusinessSearch = new TomSelect(editSelectElement, {
                        placeholder: '请先选择业务类型'
                    });
                    this.editBusinessSearch.disable();
                }
            }, 50);
        },



        // Form validation and preparation
        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            // Validate transaction date
            if (!this.newItem.transactionDate) {
                this.validationErrors.transactionDate = '请选择交易日期';
                isValid = false;
            }

            // Validate amount
            if (!this.newItem.amount || parseFloat(this.newItem.amount) <= 0) {
                this.validationErrors.amount = '请输入有效金额';
                isValid = false;
            }

            // Validate description
            if (!this.newItem.description) {
                this.validationErrors.description = '请输入交易描述';
                isValid = false;
            }

            // 当选择了业务类型但没选择具体业务时进行校验
            if (this.newItem.businessTable && !this.newItem.businessId) {
                this.validationErrors.businessId = '请选择关联的业务项';
                isValid = false;
            }

            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            // Validate transaction date
            if (!this.editedItem.transactionDate) {
                this.validationErrors.transactionDate = '请选择交易日期';
                isValid = false;
            }

            // Validate amount
            if (!this.editedItem.amount || parseFloat(this.editedItem.amount) <= 0) {
                this.validationErrors.amount = '请输入有效金额';
                isValid = false;
            }

            // Validate description
            if (!this.editedItem.description) {
                this.validationErrors.description = '请输入交易描述';
                isValid = false;
            }

            // 当选择了业务类型但没选择具体业务时进行校验
            if (this.editedItem.businessTable && !this.editedItem.businessId) {
                this.validationErrors.editBusinessId = '请选择关联的业务项';
                isValid = false;
            }

            return isValid;
        },

        prepareCreateData() {
            return {
                transactionDate: this.newItem.transactionDate,
                amount: this.newItem.amount,
                type: parseInt(this.newItem.type),
                businessTable: this.newItem.businessTable || null,
                businessId: this.newItem.businessId || null,
                description: this.newItem.description,
                paymentMethod: this.newItem.paymentMethod || null,
                remark: this.newItem.remark || null
            };
        },

        prepareUpdateData() {
            return {
                transactionDate: this.editedItem.transactionDate,
                amount: this.editedItem.amount,
                type: parseInt(this.editedItem.type),
                businessTable: this.editedItem.businessTable || null,
                businessId: this.editedItem.businessId || null,
                description: this.editedItem.description,
                paymentMethod: this.editedItem.paymentMethod || null,
                remark: this.editedItem.remark || null
            };
        },

        resetCreateForm() {
            // Get current time for default date
            const now = new Date();
            const localDateTimeFormat = formatDateTimeForLocal(now);

            this.newItem = {
                transactionDate: localDateTimeFormat,
                amount: '',
                type: 0, // Default to income
                businessTable: '',
                businessId: '',
                description: '',
                paymentMethod: '',
                remark: ''
            };

            // Reset search components
            if (this.businessSearch) {
                this.businessSearch.destroy();
                const createSelectElement = document.getElementById('business-search');
                if (createSelectElement) {
                    this.businessSearch = new TomSelect(createSelectElement, {
                        placeholder: '请先选择业务类型',
                        onChange: (value) => {
                            if (value) {
                                const option = this.businessSearch.options[value];
                                this.newItem.businessId = value;
                                this.newItem.remark = option?.remark || option?.name || '';
                            } else {
                                this.newItem.businessId = '';
                                this.newItem.remark = '';
                            }
                        }
                    });
                    this.businessSearch.disable();
                }
            }
        },

        prepareEditForm(transaction) {
            // Format dates for datetime-local input
            const formatDateForInput = (dateString) => {
                if (!dateString) return '';
                const date = new Date(dateString);
                return formatDateTimeForLocal(date);
            };

            // Clone transaction data
            const editedData = {
                id: transaction.id,
                transactionDate: formatDateForInput(transaction.transactionDate),
                amount: transaction.amount,
                type: transaction.type,
                businessTable: transaction.businessTable || '',
                businessId: transaction.businessId || '',
                description: transaction.description,
                paymentMethod: transaction.paymentMethod || '',
                remark: transaction.remark || ''
            };

            // Handle business search component for edit
            if (this.editBusinessSearch) {
                this.editBusinessSearch.destroy();
            }

            const editSelectElement = document.getElementById('edit-business-search');
            if (editSelectElement) {
                if (transaction.businessTable && transaction.businessId) {
                    const config = this.getBusinessSearchConfig(transaction.businessTable);
                    if (config) {
                        this.editBusinessSearch = new TomSelect(editSelectElement, {
                            ...config,
                            onChange: (value) => {
                                if (value) {
                                    const option = this.editBusinessSearch.options[value];
                                    this.editedItem.businessId = value;
                                    this.editedItem.remark = option?.remark || option?.name || '';
                                } else {
                                    this.editedItem.businessId = '';
                                    this.editedItem.remark = '';
                                }
                            }
                        });

                        // Add current business as an option
                        this.editBusinessSearch.addOption({
                            id: transaction.businessId,
                            name: transaction.businessName || '',
                            remark: transaction.businessName || ''
                        });
                        this.editBusinessSearch.setValue(transaction.businessId, true);
                    }
                } else {
                    this.editBusinessSearch = new TomSelect(editSelectElement, {
                        placeholder: '请先选择业务类型',
                        onChange: (value) => {
                            if (value) {
                                const option = this.editBusinessSearch.options[value];
                                this.editedItem.businessId = value;
                                this.editedItem.remark = option?.remark || option?.name || '';
                            } else {
                                this.editedItem.businessId = '';
                                this.editedItem.remark = '';
                            }
                        }
                    });
                    this.editBusinessSearch.disable();
                }
            }

            return editedData;
        },

        // Hooks for after create/update
        afterCreate(data) {
            this.fetchMonthlyStats(); // Refresh the chart
        },

        afterUpdate(data) {
            this.fetchMonthlyStats(); // Refresh the chart
        },

        // URLs for API calls
        getApiUrl() {
            return '/api/admin/transactions';
        },

        ...createResponsiveFilterMethods({
            createDefaultFilters: () => ({ ...DEFAULT_TRANSACTION_FILTERS }),
            getActiveTags() {
                const tags = this.searchQuery ? [{
                    key: 'search',
                    label: '搜索',
                    value: this.searchQuery
                }] : [];

                if (this.filters.type !== '') {
                    const typeLabel = this.transactionTypes.find(item => String(item.value) === String(this.filters.type))?.label || this.filters.type;
                    tags.push({ key: 'type', label: '交易类型', value: typeLabel });
                }

                if (this.filters.businessTable) {
                    tags.push({ key: 'businessTable', label: '业务类型', value: this.getBusinessTableLabel(this.filters.businessTable) });
                }

                return tags;
            }
        })

    }
});

// Initialize the Vue app
const vueApp = transactionTable.createApp('#app');

// Make transaction table globally accessible for search dropdowns
window.transactionTable = transactionTable;
