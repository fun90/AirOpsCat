
import { DataTable } from '/static/js/common/data-table.js';

const trafficStatsTable = new DataTable({
    data: {
        // App specific data
        stats: [],
        users: [],
        searchQuery: '',
        filterUserId: '',
        filterAccountId: '',
        startDate: '',
        endDate: '',
        totalUpload: 0,
        totalDownload: 0,
        totalUploadFormatted: '0 B',
        totalDownloadFormatted: '0 B',
        selectedStats: null,
        deleteModal: null,
        createModal: null,
        editModal: null,
        newStats: {
            userId: '',
            accountId: '',
            periodStart: '',
            periodEnd: '',
            uploadBytes: 0,
            downloadBytes: 0
        },
        editedStats: {
            id: null,
            userId: '',
            accountId: '',
            periodStart: '',
            periodEnd: '',
            uploadBytes: 0,
            downloadBytes: 0
        },
        validationErrors: {},
        userMap: {} // 用于缓存用户ID和邮箱的映射
    },
    methods: {
        // Initialize data
        initialize() {
            this.fetchUsers();
            this.fetchStats();
        },

        // Data fetching methods
        fetchStats() {
            this.loading = true;

            // Build query parameters
            const params = new URLSearchParams({
                page: this.currentPage,
                size: this.pageSize
            });

            if (this.searchQuery) {
                params.append('search', this.searchQuery);
            }

            if (this.filterUserId) {
                params.append('userId', this.filterUserId);
            }

            if (this.filterAccountId) {
                params.append('accountId', this.filterAccountId);
            }

            if (this.startDate) {
                // Convert to ISO string format for API
                const startDateTime = new Date(this.startDate);
                startDateTime.setHours(0, 0, 0, 0);
                params.append('startDate', startDateTime.toISOString());
            }

            if (this.endDate) {
                // Convert to ISO string format for API
                const endDateTime = new Date(this.endDate);
                endDateTime.setHours(23, 59, 59, 999);
                params.append('endDate', endDateTime.toISOString());
            }

            const url = this.filterUserId ?
                `/api/admin/traffic-stats/user/${this.filterUserId}?${params.toString()}` :
                `/api/admin/traffic-stats?${params.toString()}`;

            fetch(url)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('Network response was not ok');
                    }
                    return response.json();
                })
                .then(data => {
                    this.stats = data.records;
                    this.totalItems = data.total;
                    this.totalPages = data.pages;
                    this.currentPage = data.current;

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
                    if (this.users.length > 0 && !this.newStats.userId) {
                        this.newStats.userId = this.users[0].id;
                    }
                })
                .catch(error => {
                    console.error('Error fetching users:', error);
                });
        },

        getUserEmail(userId) {
            return this.userMap[userId] || userId;
        },

        // Create, edit, and delete operations
        confirmDelete(stats) {
            this.selectedStats = stats;
            this.deleteModal = new bootstrap.Modal(document.getElementById('deleteModal'));
            this.deleteModal.show();
        },

        deleteStats() {
            if (!this.selectedStats) return;

            fetch(`/api/admin/traffic-stats/${this.selectedStats.id}`, {
                method: 'DELETE'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('删除失败');
                    }

                    // Refetch the stats data to update the list and pagination
                    this.fetchStats();
                    ToastUtils.show('Success', '删除成功', 'success');

                    // Hide modal
                    this.deleteModal.hide();
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '删除失败', 'danger');
                });
        },

        openCreateStatsModal() {
            // Initialize with current date for period start/end
            const now = new Date();
            const localDateTimeFormat = now.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM

            // Reset form
            this.newStats = {
                userId: this.users.length > 0 ? this.users[0].id : '',
                accountId: '',
                periodStart: localDateTimeFormat,
                periodEnd: localDateTimeFormat,
                uploadBytes: 0,
                downloadBytes: 0
            };
            this.validationErrors = {};

            // Show modal
            this.createModal = new bootstrap.Modal(document.getElementById('createStatsModal'));
            this.createModal.show();
        },

        validateStatsForm(stats) {
            let isValid = true;
            this.validationErrors = {};

            // UserID validation
            if (!stats.userId) {
                this.validationErrors.userId = '请选择用户';
                isValid = false;
            }

            // AccountID validation
            if (!stats.accountId) {
                this.validationErrors.accountId = '账户ID不能为空';
                isValid = false;
            }

            // Period start validation
            if (!stats.periodStart) {
                this.validationErrors.periodStart = '开始时间不能为空';
                isValid = false;
            }

            // Period end validation
            if (!stats.periodEnd) {
                this.validationErrors.periodEnd = '结束时间不能为空';
                isValid = false;
            } else if (stats.periodStart && new Date(stats.periodEnd) < new Date(stats.periodStart)) {
                this.validationErrors.periodEnd = '结束时间必须晚于开始时间';
                isValid = false;
            }

            return isValid;
        },

        createStats() {
            if (!this.validateStatsForm(this.newStats)) {
                return;
            }

            // Prepare data for API
            const statsData = {
                userId: this.newStats.userId,
                accountId: this.newStats.accountId,
                periodStart: this.newStats.periodStart,
                periodEnd: this.newStats.periodEnd,
                uploadBytes: this.newStats.uploadBytes || 0,
                downloadBytes: this.newStats.downloadBytes || 0
            };

            fetch('/api/admin/traffic-stats', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(statsData)
            })
                .then(response => {
                    if (!response.ok) {
                        if (response.status === 400) {
                            return response.json().then(data => {
                                throw new Error(data.message || 'Validation error');
                            });
                        }
                        throw new Error('响应失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.fetchStats(); // Refresh the stats list
                    this.createModal.hide();
                    ToastUtils.show('Success', '创建成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '创建失败.', 'danger');
                });
        },

        openEditStatsModal(stats) {
            // Format dates for datetime-local input
            const formatDateForInput = (dateString) => {
                if (!dateString) return '';
                const date = new Date(dateString);
                return date.toISOString().slice(0, 16); // Format: YYYY-MM-DDTHH:MM
            };

            // Clone stats data to avoid direct mutation
            this.editedStats = {
                id: stats.id,
                userId: stats.userId,
                accountId: stats.accountId,
                periodStart: formatDateForInput(stats.periodStart),
                periodEnd: formatDateForInput(stats.periodEnd),
                uploadBytes: stats.uploadBytes || 0,
                downloadBytes: stats.downloadBytes || 0
            };
            this.validationErrors = {};

            // Show modal
            this.editModal = new bootstrap.Modal(document.getElementById('editStatsModal'));
            this.editModal.show();
        },

        updateStats() {
            if (!this.validateStatsForm(this.editedStats)) {
                return;
            }

            // Prepare data for API
            const statsData = {
                userId: this.editedStats.userId,
                accountId: this.editedStats.accountId,
                periodStart: this.editedStats.periodStart,
                periodEnd: this.editedStats.periodEnd,
                uploadBytes: this.editedStats.uploadBytes || 0,
                downloadBytes: this.editedStats.downloadBytes || 0
            };

            fetch(`/api/admin/traffic-stats/${this.editedStats.id}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(statsData)
            })
                .then(response => {
                    if (!response.ok) {
                        if (response.status === 400) {
                            return response.json().then(data => {
                                throw new Error(data.message || 'Validation error');
                            });
                        }
                        throw new Error('响应失败');
                    }
                    return response.json();
                })
                .then(data => {
                    // Update stats in the local array
                    const index = this.stats.findIndex(s => s.id === this.editedStats.id);
                    if (index !== -1) {
                        this.stats[index] = data;
                    }

                    this.editModal.hide();
                    ToastUtils.show('Success', '更新成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '更新失败', 'danger');
                });
        },

        mounted() {
            this.initialize();
        }
    }
});

// Initialize the Vue app
trafficStatsTable.createApp('#traffic-stats-app');