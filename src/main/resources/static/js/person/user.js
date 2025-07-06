
import { DataTable } from '/static/js/common/data-table.js';

const userTable = new DataTable({
    data: {
        entityName: 'users',
        modalIdPrefix: 'user-',
        filters: {
            role: '',
            status: ''
        },
        newItem: {
            email: '',
            password: '',
            nickName: '',
            role: 'PARTNER',
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

        getStatusBadgeClass(disabled) {
            return disabled === 0 ? 'text-bg-success' : 'text-bg-danger';
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
                role: this.newItem.role,
                disabled: this.newItem.disabled ? 1 : 0
            };
        },

        prepareUpdateData() {
            const data = {
                email: this.editedItem.email,
                nickName: this.editedItem.nickName || null,
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
                password: '',
                nickName: '',
                role: 'PARTNER',
                disabled: false
            };
        },

        prepareEditForm(user) {
            return {
                id: user.id,
                email: user.email,
                password: '', // Empty password field
                nickName: user.nickName || '',
                role: user.role || 'PARTNER',
                disabled: user.disabled
            };
        },

        // URLs for API calls
        getApiUrl() {
            return '/api/admin/users';
        },

        getToggleStatusUrl(item, action) {
            return `/api/admin/users/${item.id}/${action}`;
        }
    }
});

// Initialize the Vue app
userTable.createApp('#users-app');