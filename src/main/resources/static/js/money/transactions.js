
import { DataTable } from '/static/js/common/data-table.js';
import { createSearchDropdown, SearchDropdownPresets, ValidationRules } from '/static/js/common/search-dropdown.js';
import ApexCharts from 'https://cdn.jsdelivr.net/npm/apexcharts@4.7.0/+esm';

const transactionTable = new DataTable({
    data: {
        entityName: 'transactions',
        modalIdPrefix: 'transaction-',
        filters: {
            type: '',
            businessTable: ''
        },
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

            // Set default transactionDate for new transaction
            const now = new Date();
            this.newItem.transactionDate = now.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM

            // Initialize search components
            this.initializeSearchComponents();
        },

        // Initialize search dropdown components
        initializeSearchComponents() {
            // 创建业务搜索组件（新建时使用）
            this.businessSearch = createSearchDropdown({
                placeholder: '请先选择业务类型',
                disabled: true,
                validation: {
                    enabled: true,
                    fieldName: 'businessId',
                    validateOn: ['blur', 'change'],
                    rules: [
                        // 当业务类型已选择时，业务必须选择
                        ValidationRules.custom((value, selectedItem) => {
                            // 如果选择了业务类型但没选择具体业务项
                            if (this.newItem.businessTable && !selectedItem) {
                                return '请选择关联的业务项';
                            }
                            return true;
                        }, '请选择关联的业务项')
                    ]
                },
                onSelect: (item) => {
                    this.newItem.businessId = item.id;
                },
                onChange: (text, item) => {
                    if (!item) {
                        this.newItem.businessId = '';
                    }
                }
            });

            // 创建编辑业务搜索组件（编辑时使用）
            this.editBusinessSearch = createSearchDropdown({
                placeholder: '请先选择业务类型',
                disabled: true,
                validation: {
                    enabled: true,
                    fieldName: 'editBusinessId',
                    validateOn: ['blur', 'change'],
                    rules: [
                        // 当业务类型已选择时，业务必须选择
                        ValidationRules.custom((value, selectedItem) => {
                            // 如果选择了业务类型但没选择具体业务项
                            if (this.editedItem.businessTable && !selectedItem) {
                                return '请选择关联的业务项';
                            }
                            return true;
                        }, '请选择关联的业务项')
                    ]
                },
                onSelect: (item) => {
                    this.editedItem.businessId = item.id;
                },
                onChange: (text, item) => {
                    if (!item) {
                        this.editedItem.businessId = '';
                    }
                }
            });

            // 延迟绑定DOM，确保模板已渲染
            setTimeout(() => {
                this.businessSearch.bindToDOM('businessSearch', 'businessId', this);
                this.editBusinessSearch.bindToDOM('editBusinessSearch', 'editBusinessId', this);
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

            const options = {
                chart: {
                    type: 'line',
                    height: 350,
                    toolbar: {
                        show: false
                    },
                    background: 'transparent'
                },
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
                            colors: '#8e8da4'
                        }
                    }
                },
                yaxis: {
                    labels: {
                        style: {
                            colors: '#8e8da4'
                        },
                        formatter: function(value) {
                            return '¥' + value.toFixed(2);
                        }
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
                        colors: '#8e8da4'
                    }
                },
                grid: {
                    borderColor: '#e0e6ed',
                    strokeDashArray: 5
                },
                tooltip: {
                    shared: true,
                    intersect: false,
                    y: {
                        formatter: function(value) {
                            return '¥' + value.toFixed(2);
                        }
                    }
                }
            };

            this.trendsChart = new ApexCharts(document.querySelector('#trends-chart'), options);
            this.trendsChart.render();
        },

        // Handle business table change event
        onBusinessTableChange() {
            this.newItem.businessId = '';
            
            if (this.newItem.businessTable) {
                // 根据业务类型配置搜索组件
                const preset = this.getBusinessSearchPreset(this.newItem.businessTable);
                if (preset) {
                    // 重新配置搜索组件
                    this.businessSearch.options = { ...this.businessSearch.options, ...preset };
                    this.businessSearch.setDisabled(false);
                    this.businessSearch.options.placeholder = preset.placeholder;
                    this.businessSearch.init();
                }
            } else {
                this.businessSearch.setDisabled(true);
                this.businessSearch.options.placeholder = '请先选择业务类型';
            }
        },

        // Handle edit business table change event
        onEditBusinessTableChange() {
            this.editedItem.businessId = '';
            
            if (this.editedItem.businessTable) {
                // 根据业务类型配置搜索组件
                const preset = this.getBusinessSearchPreset(this.editedItem.businessTable);
                if (preset) {
                    // 重新配置搜索组件
                    this.editBusinessSearch.options = { ...this.editBusinessSearch.options, ...preset };
                    this.editBusinessSearch.setDisabled(false);
                    this.editBusinessSearch.options.placeholder = preset.placeholder;
                    this.editBusinessSearch.init();
                }
            } else {
                this.editBusinessSearch.setDisabled(true);
                this.editBusinessSearch.options.placeholder = '请先选择业务类型';
            }
        },

        // Get business search preset configuration
        getBusinessSearchPreset(businessTable) {
            switch (businessTable) {
                case 'account':
                    return SearchDropdownPresets.account();
                case 'domain':
                    return SearchDropdownPresets.domain();
                case 'server':
                    return SearchDropdownPresets.server();
                default:
                    return null;
            }
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

            // 校验搜索下拉框组件
            if (this.businessSearch) {
                const businessValid = this.businessSearch.validate();
                if (!businessValid) {
                    isValid = false;
                }
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

            // 校验搜索下拉框组件
            if (this.editBusinessSearch) {
                const businessValid = this.editBusinessSearch.validate();
                if (!businessValid) {
                    isValid = false;
                }
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
            const localDateTimeFormat = now.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM

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
                this.businessSearch.clear();
                this.businessSearch.setDisabled(true);
            }
        },

        prepareEditForm(transaction) {
            // Format dates for datetime-local input
            const formatDateForInput = (dateString) => {
                if (!dateString) return '';
                const date = new Date(dateString);
                return date.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM
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
                if (transaction.businessTable && transaction.businessId) {
                    // 配置编辑搜索组件
                    const preset = this.getBusinessSearchPreset(transaction.businessTable);
                    if (preset) {
                        this.editBusinessSearch.options = { ...this.editBusinessSearch.options, ...preset };
                        this.editBusinessSearch.setDisabled(false);
                        this.editBusinessSearch.init();
                        
                        // 设置已选中的业务信息
                        this.editBusinessSearch.setValue(transaction.businessName || '', {
                            id: transaction.businessId,
                            name: transaction.businessName || ''
                        });
                    }
                } else {
                    this.editBusinessSearch.clear();
                    this.editBusinessSearch.setDisabled(true);
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
        }
    }
});

// Initialize the Vue app
const vueApp = transactionTable.createApp('#transactions-app');

// Make transaction table globally accessible for search dropdowns
window.transactionTable = transactionTable;