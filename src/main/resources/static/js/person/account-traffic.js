
import { DataTable } from '/static/js/common/data-table.js';

const trafficStatsTable = new DataTable({
    data: {
        entityName: 'traffic-stats',
        modalIdPrefix: 'traffic-stats-',
        filters: {
            userId: '',
            startDate: '',
            endDate: ''
        },
        users: [],
        totalUpload: 0,
        totalDownload: 0,
        totalUploadFormatted: '0 B',
        totalDownloadFormatted: '0 B',
        userMap: {}, // 用于缓存用户ID和邮箱的映射
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
        // Initialize data
        initialize() {
            this.fetchUsers();
        },

        // Override the base fetchRecords method to handle traffic stats specific logic
        fetchRecords() {
            this.loading = true;

            // Build query parameters
            const params = new URLSearchParams({
                page: this.currentPage,
                size: this.pageSize
            });

            // Add search query if present
            if (this.searchQuery) {
                params.append('search', this.searchQuery);
            }

            // Add specific filters
            if (this.filters.userId) {
                params.append('userId', this.filters.userId);
            }

            if (this.filters.startDate) {
                // Convert to ISO string format for API
                const startDateTime = new Date(this.filters.startDate);
                startDateTime.setHours(0, 0, 0, 0);
                params.append('startDate', startDateTime.toISOString());
            }

            if (this.filters.endDate) {
                // Convert to ISO string format for API
                const endDateTime = new Date(this.filters.endDate);
                endDateTime.setHours(23, 59, 59, 999);
                params.append('endDate', endDateTime.toISOString());
            }

            const url = this.filters.userId ?
                `/api/admin/traffic-stats/user/${this.filters.userId}?${params.toString()}` :
                `/api/admin/traffic-stats?${params.toString()}`;

            fetch(url)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('Network response was not ok');
                    }
                    return response.json();
                })
                .then(data => {
                    this.records = data.records || [];
                    this.totalItems = data.total || 0;
                    this.startIndex = (this.currentPage - 1) * this.pageSize + 1;
                    this.endIndex = Math.min(this.startIndex + this.pageSize - 1, this.totalItems);
                    this.totalPages = data.pages || 0;
                    this.currentPage = data.current || 1;

                    // 总流量统计数据，只有在按用户或账户筛选时才有
                    if (data.totalUpload !== undefined) {
                        this.totalUpload = data.totalUpload;
                        this.totalDownload = data.totalDownload;
                        this.totalUploadFormatted = data.totalUploadFormatted;
                        this.totalDownloadFormatted = data.totalDownloadFormatted;
                    } else {
                        this.totalUpload = 0;
                        this.totalDownload = 0;
                        this.totalUploadFormatted = '0 B';
                        this.totalDownloadFormatted = '0 B';
                    }

                    this.loading = false;
                })
                .catch(error => {
                    console.error('Error fetching traffic stats:', error);
                    ToastUtils.show('Error', 'Failed to load traffic stats.', 'danger');
                    this.loading = false;
                });
        },

        fetchUsers() {
            fetch('/api/admin/users')
                .then(response => response.json())
                .then(data => {
                    this.users = data.records;

                    // 建立用户ID到邮箱的映射
                    this.users.forEach(user => {
                        this.userMap[user.id] = user.email || user.nickName || user.id;
                    });

                    // 如果是第一次加载且当前没有选中用户，选择第一个用户
                    if (this.users.length > 0 && !this.newItem.userId) {
                        this.newItem.userId = this.users[0].id;
                    }
                })
                .catch(error => {
                    console.error('Error fetching users:', error);
                });
        },

        getUserEmail(userId) {
            return this.userMap[userId] || userId;
        },

        // Override validation methods for traffic stats specific validation
        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            // UserID validation
            if (!this.newItem.userId) {
                this.validationErrors.userId = '请选择用户';
                isValid = false;
            }

            // AccountID validation
            if (!this.newItem.accountId) {
                this.validationErrors.accountId = '账户ID不能为空';
                isValid = false;
            }

            // Period start validation
            if (!this.newItem.periodStart) {
                this.validationErrors.periodStart = '开始时间不能为空';
                isValid = false;
            }

            // Period end validation
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

            // UserID validation
            if (!this.editedItem.userId) {
                this.validationErrors.userId = '请选择用户';
                isValid = false;
            }

            // AccountID validation
            if (!this.editedItem.accountId) {
                this.validationErrors.accountId = '账户ID不能为空';
                isValid = false;
            }

            // Period start validation
            if (!this.editedItem.periodStart) {
                this.validationErrors.periodStart = '开始时间不能为空';
                isValid = false;
            }

            // Period end validation
            if (!this.editedItem.periodEnd) {
                this.validationErrors.periodEnd = '结束时间不能为空';
                isValid = false;
            } else if (this.editedItem.periodStart && new Date(this.editedItem.periodEnd) < new Date(this.editedItem.periodStart)) {
                this.validationErrors.periodEnd = '结束时间必须晚于开始时间';
                isValid = false;
            }

            return isValid;
        },

        // Override prepareCreateData to format the data for traffic stats API
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

        // Override prepareUpdateData to format the data for traffic stats API
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
            // Initialize with current date for period start/end
            const now = new Date();
            const localDateTimeFormat = now.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM

            this.newItem = {
                userId: this.users.length > 0 ? this.users[0].id : '',
                accountId: '',
                periodStart: localDateTimeFormat,
                periodEnd: localDateTimeFormat,
                uploadBytes: 0,
                downloadBytes: 0
            };
        },

        // Override prepareEditForm to format dates for datetime-local input
        prepareEditForm(record) {
            // Format dates for datetime-local input
            const formatDateForInput = (dateString) => {
                if (!dateString) return '';
                const date = new Date(dateString);
                return date.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM
            };

            // Return the formatted data object
            return {
                id: record.id,
                userId: record.userId,
                accountId: record.accountId,
                periodStart: formatDateForInput(record.periodStart),
                periodEnd: formatDateForInput(record.periodEnd),
                uploadBytes: record.uploadBytes || 0,
                downloadBytes: record.downloadBytes || 0
            };
        },

        // Override getApiUrl to return the correct API endpoint
        getApiUrl() {
            return '/api/admin/traffic-stats';
        }
    }
});

// Initialize the Vue app
trafficStatsTable.createApp('#traffic-stats-app');