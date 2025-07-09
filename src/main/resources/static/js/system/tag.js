import { DataTable } from '/static/js/common/data-table.js';
import { Modal } from 'https://cdn.jsdelivr.net/npm/@tabler/core@1.3.2/dist/js/tabler.esm.min.js';

const tagTable = new DataTable({
    data: {
        entityName: 'tags',
        modalIdPrefix: 'tag-',
        filters: {
            status: ''
        },
        stats: {
            total: 0,
            enabled: 0,
            disabled: 0
        },
        // 标签关联数据
        associationData: {
            nodes: [],
            accounts: []
        },
        tagAssociationModal: null,
        newItem: {
            name: '',
            description: '',
            color: '#6c757d',
            disabled: false
        }
    },
    methods: {
        // Initialize
        initialize() {
            // Any additional initialization needed
        },

        // Status display methods
        getStatusDescription(tag) {
            return tag.disabled === 1 ? '禁用' : '启用';
        },

        getStatusBadgeClass(tag) {
            return tag.disabled === 1 ? 'text-bg-secondary' : 'text-bg-success';
        },

        // Account status methods for association modal
        getAccountStatusDescription(account) {
            if (account.disabled === 1) {
                return "已禁用";
            }

            if (!account.toDate) {
                return "永不过期";
            }

            const now = new Date();
            const toDate = new Date(account.toDate);
            const timeDiff = toDate.getTime() - now.getTime();
            const days = Math.floor(timeDiff / (1000 * 60 * 60 * 24));

            if (days < 0) {
                return "已过期";
            } else if (days <= 7) {
                return days + " 天后过期";
            } else {
                return "正常";
            }
        },

        getAccountStatusBadgeClass(account) {
            if (account.disabled === 1) {
                return 'text-bg-secondary';
            }

            if (!account.toDate) {
                return 'text-bg-success';
            }

            const now = new Date();
            const toDate = new Date(account.toDate);
            const timeDiff = toDate.getTime() - now.getTime();
            const days = Math.floor(timeDiff / (1000 * 60 * 60 * 24));

            if (days < 0) {
                return 'text-bg-danger';
            } else if (days <= 7) {
                return 'text-bg-warning';
            } else {
                return 'text-bg-success';
            }
        },

        // Tag Association Modal
        openTagAssociationModal(tag) {
            this.selectedItem = tag;
            this.loadTagAssociations(tag.id);
            this.tagAssociationModal = new Modal(document.getElementById('tagAssociationModal'));
            this.tagAssociationModal.show();
        },

        loadTagAssociations(tagId) {
            // Load associated nodes
            fetch(`/api/admin/tags/${tagId}/nodes`)
                .then(response => response.json())
                .then(data => {
                    this.associationData.nodes = data;
                })
                .catch(error => {
                    console.error('Error loading tag nodes:', error);
                    this.associationData.nodes = [];
                });

            // Load associated accounts
            fetch(`/api/admin/tags/${tagId}/accounts`)
                .then(response => response.json())
                .then(data => {
                    this.associationData.accounts = data;
                })
                .catch(error => {
                    console.error('Error loading tag accounts:', error);
                    this.associationData.accounts = [];
                });
        },

        removeTagFromNode(tagId, nodeId) {
            if (!confirm('确定要移除此节点关联吗？')) {
                return;
            }

            fetch(`/api/admin/tags/${tagId}/nodes/${nodeId}`, {
                method: 'DELETE'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('移除关联失败');
                    }
                    // Remove from local array
                    this.associationData.nodes = this.associationData.nodes.filter(node => node.id !== nodeId);
                    ToastUtils.show('Success', '移除节点关联成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '移除节点关联失败', 'danger');
                });
        },

        removeTagFromAccount(tagId, accountId) {
            if (!confirm('确定要移除此账户关联吗？')) {
                return;
            }

            fetch(`/api/admin/tags/${tagId}/accounts/${accountId}`, {
                method: 'DELETE'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('移除关联失败');
                    }
                    // Remove from local array
                    this.associationData.accounts = this.associationData.accounts.filter(account => account.id !== accountId);
                    ToastUtils.show('Success', '移除账户关联成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '移除账户关联失败', 'danger');
                });
        },

        // Form validation
        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.newItem.name || this.newItem.name.trim() === '') {
                this.validationErrors.name = '标签名称不能为空';
                isValid = false;
            }

            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.editedItem.name || this.editedItem.name.trim() === '') {
                this.validationErrors.name = '标签名称不能为空';
                isValid = false;
            }

            return isValid;
        },

        // Data preparation
        prepareCreateData() {
            return {
                name: this.newItem.name.trim(),
                description: this.newItem.description || null,
                color: this.newItem.color || null,
                disabled: this.newItem.disabled ? 1 : 0
            };
        },

        prepareUpdateData() {
            return {
                name: this.editedItem.name.trim(),
                description: this.editedItem.description || null,
                color: this.editedItem.color || null,
                disabled: this.editedItem.disabled
            };
        },

        resetCreateForm() {
            this.newItem = {
                name: '',
                description: '',
                color: '#6c757d',
                disabled: false
            };
        },

        prepareEditForm(tag) {
            return {
                id: tag.id,
                name: tag.name,
                description: tag.description || '',
                color: tag.color || '#6c757d',
                disabled: tag.disabled
            };
        },

        // API URLs
        getApiUrl() {
            return '/api/admin/tags';
        },

        getToggleStatusUrl(item, action) {
            return `/api/admin/tags/${item.id}/${action}`;
        }
    }
});

// Initialize the Vue app
tagTable.createApp('#tags-app'); 