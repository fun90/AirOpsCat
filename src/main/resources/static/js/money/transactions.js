
import { DataTable } from '/static/js/common/data-table.js';

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
        businessItems: [],
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

        // Load related business items based on selected business table
        fetchBusinessItems(table) {
            if (!table) {
                this.businessItems = [];
                return;
            }

            let url = '';
            switch (table) {
                case 'account':
                    url = '/api/admin/accounts';
                    break;
                case 'domain':
                    url = '/api/admin/domains';
                    break;
                case 'server':
                    url = '/api/admin/servers';
                    break;
                default:
                    return;
            }

            fetch(url)
                .then(response => response.json())
                .then(data => {
                    if (data.records && Array.isArray(data.records)) {
                        this.businessItems = data.records.map(item => {
                            let name = '';
                            if (table === 'account') {
                                name = item.uuid || item.id;
                            } else if (table === 'domain') {
                                name = item.domain || item.id;
                            } else if (table === 'server') {
                                name = item.ip + (item.name ? ` (${item.name})` : '');
                            }

                            return {
                                id: item.id,
                                name: name
                            };
                        });
                    } else {
                        this.businessItems = [];
                    }
                })
                .catch(error => {
                    console.error(`Error fetching ${table} items:`, error);
                    this.businessItems = [];
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

            const ctx = document.getElementById('trends-chart').getContext('2d');
            this.trendsChart = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: months,
                    datasets: [
                        {
                            label: '收入',
                            data: incomeData,
                            backgroundColor: 'rgba(40, 167, 69, 0.5)',
                            borderColor: 'rgba(40, 167, 69, 1)',
                            borderWidth: 1
                        },
                        {
                            label: '支出',
                            data: expenseData,
                            backgroundColor: 'rgba(220, 53, 69, 0.5)',
                            borderColor: 'rgba(220, 53, 69, 1)',
                            borderWidth: 1
                        },
                        {
                            label: '余额',
                            data: balanceData,
                            type: 'line',
                            backgroundColor: 'rgba(255, 193, 7, 0.5)',
                            borderColor: 'rgba(255, 193, 7, 1)',
                            borderWidth: 2,
                            fill: false
                        }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    scales: {
                        y: {
                            beginAtZero: true
                        }
                    }
                }
            });
        },

        // Handle business table change event
        onBusinessTableChange() {
            this.newItem.businessId = '';
            this.fetchBusinessItems(this.newItem.businessTable);
        },

        // Handle edit business table change event
        onEditBusinessTableChange() {
            this.editedItem.businessId = '';
            this.fetchBusinessItems(this.editedItem.businessTable);
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

            this.businessItems = [];
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

            // Fetch related business items if needed
            if (transaction.businessTable) {
                this.fetchBusinessItems(transaction.businessTable);
            } else {
                this.businessItems = [];
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
transactionTable.createApp('#transactions-app');