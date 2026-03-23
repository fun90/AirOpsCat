
import { DataTable } from '/static/js/common/data-table.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';

const LOCK_TIME_DURATION = 30;
const DEFAULT_USER_FILTERS = Object.freeze({
    role: '',
    status: ''
});

const userTable = new DataTable({
    data: {
        entityName: 'users',
        modalIdPrefix: 'user-',
        filters: {
            role: '',
            status: ''
        },
        stats: {
            total: 0,
            active: 0,
            locked: 0,
            disabled: 0
        },
        newItem: {
            email: '',
            password: '',
            nickName: '',
            remarkName: '',
            remark: '',
            role: 'VIP',
            disabled: false
        }
    },
    methods: {
        // Initialize any additional data
        initialize() {
            // Any additional initialization needed
        },

        // Get Badge classes
        getRoleBadgeClass(role) {
            switch (role && role.toUpperCase()) {
                case 'ADMIN': return 'text-bg-purple';
                case 'PARTNER': return 'text-bg-indigo';
                default: return 'text-bg-blue'; // VIP or other roles
            }
        },

        getRoleLabel(role) {
            return role || 'PARTNER';
        },

        isUserLocked(user) {
            if (!user || !user.lockTime || (user.disabled || 0) === 1) {
                return false;
            }

            const lockTime = new Date(user.lockTime);
            return Date.now() < lockTime.getTime() + LOCK_TIME_DURATION * 60 * 1000;
        },

        getStatusBadgeClass(user) {
            if ((user.disabled || 0) === 1) {
                return 'bg-danger-lt';
            }
            if (this.isUserLocked(user)) {
                return 'bg-warning-lt';
            }
            return 'bg-success-lt';
        },

        getStatusDescription(user) {
            if ((user.disabled || 0) === 1) {
                return '已禁用';
            }
            if (this.isUserLocked(user)) {
                return '锁定中';
            }
            return '正常';
        },

        getFailedAttemptsText(user) {
            const attempts = user.failedAttempts || 0;
            return attempts > 0 ? `失败 ${attempts} 次` : '无失败记录';
        },

        getLockRemainingText(user) {
            if (!this.isUserLocked(user)) {
                return '';
            }

            const lockTime = new Date(user.lockTime);
            const unlockAt = lockTime.getTime() + LOCK_TIME_DURATION * 60 * 1000;
            const remainingMs = Math.max(unlockAt - Date.now(), 0);
            const remainingMinutes = Math.ceil(remainingMs / (60 * 1000));
            return `${remainingMinutes} 分钟后解锁`;
        },

        filterByStatus(status) {
            this.filters.status = status;
            this.currentPage = 1;
            this.fetchRecords();
        },

        // Form validation and preparation
        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            // Email validation
            if (!this.newItem.email || !this.newItem.email.trim()) {
                this.validationErrors.email = '邮箱地址不能为空';
                isValid = false;
            } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.newItem.email)) {
                this.validationErrors.email = '请输入有效的邮箱地址';
                isValid = false;
            }

            // Password validation
            if (!this.newItem.password || this.newItem.password.trim().length < 6) {
                this.validationErrors.password = '密码长度至少为6个字符';
                isValid = false;
            }

            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            // Email validation
            if (!this.editedItem.email || !this.editedItem.email.trim()) {
                this.validationErrors.email = '邮箱地址不能为空';
                isValid = false;
            } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.editedItem.email)) {
                this.validationErrors.email = '请输入有效的邮箱地址';
                isValid = false;
            }

            // Password validation (only if provided)
            if (this.editedItem.password && this.editedItem.password.trim().length > 0 && this.editedItem.password.trim().length < 6) {
                this.validationErrors.password = '密码长度至少为6个字符';
                isValid = false;
            }

            return isValid;
        },

        prepareCreateData() {
            return {
                email: this.newItem.email,
                password: this.newItem.password,
                nickName: this.newItem.nickName || null,
                remarkName: this.newItem.remarkName || null,
                remark: this.newItem.remark || null,
                role: this.newItem.role,
                disabled: this.newItem.disabled ? 1 : 0
            };
        },

        prepareUpdateData() {
            const data = {
                email: this.editedItem.email,
                nickName: this.editedItem.nickName || null,
                remarkName: this.editedItem.remarkName || null,
                remark: this.editedItem.remark || null,
                role: this.editedItem.role,
                disabled: this.editedItem.disabled
            };

            // Only include password if it's provided
            if (this.editedItem.password) {
                data.password = this.editedItem.password;
            }

            return data;
        },

        resetCreateForm() {
            this.newItem = {
                email: '',
                password: Math.random().toString(36).slice(-16),
                nickName: '',
                remarkName: '',
                remark: '',
                role: 'VIP',
                disabled: false
            };
        },

        prepareEditForm(user) {
            return {
                id: user.id,
                email: user.email,
                password: '', // Empty password field
                nickName: user.nickName || '',
                remarkName: user.remarkName || '',
                remark: user.remark || '',
                role: user.role || 'PARTNER',
                disabled: user.disabled,
                failedAttempts: user.failedAttempts || 0,
                lockTime: user.lockTime || null
            };
        },

        // URLs for API calls
        getApiUrl() {
            return '/api/admin/users';
        },

        getToggleStatusUrl(item, action) {
            return `/api/admin/users/${item.id}/${action}`;
        },

        ...createResponsiveFilterMethods({
            createDefaultFilters: () => ({ ...DEFAULT_USER_FILTERS }),
            getActiveTags() {
                const tags = this.searchQuery ? [{
                    key: 'search',
                    label: '搜索',
                    value: this.searchQuery
                }] : [];

                if (this.filters.role) {
                    tags.push({ key: 'role', label: '角色', value: this.filters.role });
                }

                if (this.filters.status) {
                    const statusLabelMap = {
                        active: '正常',
                        locked: '锁定中',
                        disabled: '已禁用'
                    };
                    tags.push({ key: 'status', label: '状态', value: statusLabelMap[this.filters.status] || this.filters.status });
                }

                return tags;
            }
        })
    }
});

// Initialize the Vue app
userTable.createApp('#app');
