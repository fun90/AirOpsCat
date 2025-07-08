
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
        filteredBusinessItems: [],
        filteredEditBusinessItems: [],
        businessSearchText: '',
        editBusinessSearchText: '',
        showBusinessDropdown: false,
        showEditBusinessDropdown: false,
        selectedBusinessIndex: -1,
        selectedEditBusinessIndex: -1,
        businessSearchLoading: false,
        editBusinessSearchLoading: false,
        searchCache: {},
        debounceTimers: {},
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

            // Initialize debounced search functions
            this.debouncedBusinessSearch = this.debounce(this.performBusinessSearch, 300, 'businessSearch');
            this.debouncedEditBusinessSearch = this.debounce(this.performEditBusinessSearch, 300, 'editBusinessSearch');
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
            this.businessSearchText = '';
            this.selectedBusinessIndex = -1;
            this.showBusinessDropdown = false;
            this.filteredBusinessItems = [];
            this.businessSearchLoading = false;
            
            // Clear related cache
            this.clearCacheForTable(this.newItem.businessTable);
        },

        // Handle edit business table change event
        onEditBusinessTableChange() {
            this.editedItem.businessId = '';
            this.editBusinessSearchText = '';
            this.selectedEditBusinessIndex = -1;
            this.showEditBusinessDropdown = false;
            this.filteredEditBusinessItems = [];
            this.editBusinessSearchLoading = false;
            
            // Clear related cache
            this.clearCacheForTable(this.editedItem.businessTable);
        },

        // Clear cache for specific table
        clearCacheForTable(table) {
            Object.keys(this.searchCache).forEach(key => {
                if (key.startsWith(`${table}-`)) {
                    delete this.searchCache[key];
                }
            });
        },

        // Debounce function for API calls
        debounce(func, delay, key) {
            return (...args) => {
                if (this.debounceTimers[key]) {
                    clearTimeout(this.debounceTimers[key]);
                }
                this.debounceTimers[key] = setTimeout(() => func.apply(this, args), delay);
            };
        },

        // Search business items via API
        async searchBusinessItems(table, query) {
            if (!table || !query || query.length < 2) {
                return [];
            }

            // Check cache first
            const cacheKey = `${table}-${query.toLowerCase()}`;
            if (this.searchCache[cacheKey]) {
                return this.searchCache[cacheKey];
            }

            try {
                let url = '';
                switch (table) {
                    case 'account':
                        url = `/api/admin/accounts?search=${encodeURIComponent(query)}&size=20`;
                        break;
                    case 'domain':
                        url = `/api/admin/domains?search=${encodeURIComponent(query)}&size=20`;
                        break;
                    case 'server':
                        url = `/api/admin/servers?search=${encodeURIComponent(query)}&size=20`;
                        break;
                    default:
                        return [];
                }

                const response = await fetch(url);
                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                }

                const data = await response.json();
                let searchResults = [];

                if (data.records && Array.isArray(data.records)) {
                    searchResults = data.records.map(item => {
                        let name = '';
                        if (table === 'account') {
                            name = item.remark;
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
                }

                // Cache the results
                this.searchCache[cacheKey] = searchResults;
                return searchResults;

            } catch (error) {
                console.error(`Error searching ${table} items:`, error);
                return [];
            }
        },

        // Filter business items based on search text (fallback for local filtering)
        filterBusinessItems(searchText, items) {
            if (!searchText) {
                return items;
            }
            return items.filter(item => 
                item.name.toLowerCase().includes(searchText.toLowerCase())
            );
        },

        // Handle business search input for create modal
        onBusinessSearch() {
            this.selectedBusinessIndex = -1;
            
            // Clear selected business if search text doesn't match exactly
            if (this.businessSearchText) {
                const exactMatch = this.filteredBusinessItems.find(item => item.name === this.businessSearchText);
                this.newItem.businessId = exactMatch ? exactMatch.id : '';
            } else {
                this.newItem.businessId = '';
                this.filteredBusinessItems = [];
                this.showBusinessDropdown = false;
                return;
            }

            // Use debounced search for API calls
            if (this.newItem.businessTable && this.businessSearchText.length >= 2) {
                this.debouncedBusinessSearch();
            } else {
                this.filteredBusinessItems = [];
                this.showBusinessDropdown = false;
            }
        },

        // Handle business search input for edit modal
        onEditBusinessSearch() {
            this.selectedEditBusinessIndex = -1;
            
            // Clear selected business if search text doesn't match exactly
            if (this.editBusinessSearchText) {
                const exactMatch = this.filteredEditBusinessItems.find(item => item.name === this.editBusinessSearchText);
                this.editedItem.businessId = exactMatch ? exactMatch.id : '';
            } else {
                this.editedItem.businessId = '';
                this.filteredEditBusinessItems = [];
                this.showEditBusinessDropdown = false;
                return;
            }

            // Use debounced search for API calls
            if (this.editedItem.businessTable && this.editBusinessSearchText.length >= 2) {
                this.debouncedEditBusinessSearch();
            } else {
                this.filteredEditBusinessItems = [];
                this.showEditBusinessDropdown = false;
            }
        },

        // Perform actual business search for create modal
        async performBusinessSearch() {
            if (!this.newItem.businessTable || !this.businessSearchText || this.businessSearchText.length < 2) {
                return;
            }

            this.businessSearchLoading = true;
            try {
                const results = await this.searchBusinessItems(this.newItem.businessTable, this.businessSearchText);
                this.filteredBusinessItems = results;
                this.showBusinessDropdown = results.length > 0;
            } catch (error) {
                console.error('Business search failed:', error);
                this.filteredBusinessItems = [];
                this.showBusinessDropdown = false;
            } finally {
                this.businessSearchLoading = false;
            }
        },

        // Perform actual business search for edit modal
        async performEditBusinessSearch() {
            if (!this.editedItem.businessTable || !this.editBusinessSearchText || this.editBusinessSearchText.length < 2) {
                return;
            }

            this.editBusinessSearchLoading = true;
            try {
                const results = await this.searchBusinessItems(this.editedItem.businessTable, this.editBusinessSearchText);
                this.filteredEditBusinessItems = results;
                this.showEditBusinessDropdown = results.length > 0;
            } catch (error) {
                console.error('Edit business search failed:', error);
                this.filteredEditBusinessItems = [];
                this.showEditBusinessDropdown = false;
            } finally {
                this.editBusinessSearchLoading = false;
            }
        },

        // Handle focus events for create modal
        onBusinessFocus() {
            if (this.newItem.businessTable) {
                if (this.businessSearchText && this.businessSearchText.length >= 2) {
                    // Trigger search if there's already text
                    this.debouncedBusinessSearch();
                } else if (this.filteredBusinessItems.length > 0) {
                    // Show cached results if available
                    this.showBusinessDropdown = true;
                }
            }
        },

        // Handle blur events for create modal
        onBusinessBlur() {
            // Delay hiding to allow for item selection
            setTimeout(() => {
                this.showBusinessDropdown = false;
                this.selectedBusinessIndex = -1;
            }, 200);
        },

        // Handle focus events for edit modal
        onEditBusinessFocus() {
            if (this.editedItem.businessTable) {
                if (this.editBusinessSearchText && this.editBusinessSearchText.length >= 2) {
                    // Trigger search if there's already text
                    this.debouncedEditBusinessSearch();
                } else if (this.filteredEditBusinessItems.length > 0) {
                    // Show cached results if available
                    this.showEditBusinessDropdown = true;
                }
            }
        },

        // Handle blur events for edit modal
        onEditBusinessBlur() {
            // Delay hiding to allow for item selection
            setTimeout(() => {
                this.showEditBusinessDropdown = false;
                this.selectedEditBusinessIndex = -1;
            }, 200);
        },

        // Handle keyboard navigation for create modal
        onBusinessKeydown(event) {
            if (!this.showBusinessDropdown) return;

            switch (event.key) {
                case 'ArrowDown':
                    event.preventDefault();
                    this.selectedBusinessIndex = Math.min(
                        this.selectedBusinessIndex + 1, 
                        this.filteredBusinessItems.length - 1
                    );
                    break;
                case 'ArrowUp':
                    event.preventDefault();
                    this.selectedBusinessIndex = Math.max(this.selectedBusinessIndex - 1, 0);
                    break;
                case 'Enter':
                    event.preventDefault();
                    if (this.selectedBusinessIndex >= 0 && this.selectedBusinessIndex < this.filteredBusinessItems.length) {
                        this.selectBusiness(this.filteredBusinessItems[this.selectedBusinessIndex]);
                    }
                    break;
                case 'Escape':
                    event.preventDefault();
                    this.showBusinessDropdown = false;
                    this.selectedBusinessIndex = -1;
                    break;
            }
        },

        // Handle keyboard navigation for edit modal
        onEditBusinessKeydown(event) {
            if (!this.showEditBusinessDropdown) return;

            switch (event.key) {
                case 'ArrowDown':
                    event.preventDefault();
                    this.selectedEditBusinessIndex = Math.min(
                        this.selectedEditBusinessIndex + 1, 
                        this.filteredEditBusinessItems.length - 1
                    );
                    break;
                case 'ArrowUp':
                    event.preventDefault();
                    this.selectedEditBusinessIndex = Math.max(this.selectedEditBusinessIndex - 1, 0);
                    break;
                case 'Enter':
                    event.preventDefault();
                    if (this.selectedEditBusinessIndex >= 0 && this.selectedEditBusinessIndex < this.filteredEditBusinessItems.length) {
                        this.selectEditBusiness(this.filteredEditBusinessItems[this.selectedEditBusinessIndex]);
                    }
                    break;
                case 'Escape':
                    event.preventDefault();
                    this.showEditBusinessDropdown = false;
                    this.selectedEditBusinessIndex = -1;
                    break;
            }
        },

        // Select business item for create modal
        selectBusiness(business) {
            this.businessSearchText = business.name;
            this.newItem.businessId = business.id;
            this.showBusinessDropdown = false;
            this.selectedBusinessIndex = -1;
        },

        // Select business item for edit modal
        selectEditBusiness(business) {
            this.editBusinessSearchText = business.name;
            this.editedItem.businessId = business.id;
            this.showEditBusinessDropdown = false;
            this.selectedEditBusinessIndex = -1;
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

            // Reset search related fields
            this.businessItems = [];
            this.filteredBusinessItems = [];
            this.filteredEditBusinessItems = [];
            this.businessSearchText = '';
            this.editBusinessSearchText = '';
            this.showBusinessDropdown = false;
            this.showEditBusinessDropdown = false;
            this.selectedBusinessIndex = -1;
            this.selectedEditBusinessIndex = -1;
            this.businessSearchLoading = false;
            this.editBusinessSearchLoading = false;
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

            // Handle business data if available
            if (transaction.businessTable && transaction.businessId) {
                // Set the search text to the business name from businessName field
                this.editBusinessSearchText = transaction.businessName || '';
                // Clear the filtered list initially
                this.filteredEditBusinessItems = [];
            } else {
                this.filteredEditBusinessItems = [];
                this.editBusinessSearchText = '';
            }

            // Reset edit search related fields
            this.showEditBusinessDropdown = false;
            this.selectedEditBusinessIndex = -1;

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