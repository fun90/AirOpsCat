import { DataTable } from '/static/js/common/data-table.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';

const configTable = new DataTable({
    data: {
        entityName: 'server-configs',
        modalIdPrefix: 'server-config-',
        filters: {
            configType: '',
            search: ''
        },
        servers: [],
        configTypes: [],
        stats: {
            total: 0,
            xray: 0,
            hysteria: 0,
            hysteria2: 0
        },
        // Form data
        newItem: {
            serverId: '',
            config: '',
            configType: '',
            path: ''
        },
        // Additional data for config management
        viewConfigModal: null,
        uploading: false,
        configViewMode: 'formatted', // 'formatted' | 'raw'
        configCollapsed: false,
        uploadProgress: 0,
        uploadProgressMessage: ''
    },
    methods: {
        // Initialize additional data
        initialize() {
            this.fetchServers();
            this.fetchConfigTypes();
            this.fetchStats();
        },

        // Fetch data methods
        fetchServers() {
            fetch('/api/admin/server-configs/servers')
                .then(response => response.json())
                .then(data => {
                    this.servers = data;

                    // 如果是第一次加载且当前没有选中服务器，选择第一个服务器
                    if (this.servers.length > 0 && !this.newItem.serverId) {
                        this.newItem.serverId = this.servers[0].id;
                    }
                })
                .catch(error => {
                    console.error('Error fetching servers:', error);
                });
        },

        fetchConfigTypes() {
            fetch('/api/admin/server-configs/types')
                .then(response => response.json())
                .then(data => {
                    this.configTypes = data;
                })
                .catch(error => {
                    console.error('Error fetching config types:', error);
                });
        },

        fetchStats() {
            fetch('/api/admin/server-configs/stats')
                .then(response => response.json())
                .then(data => {
                    this.stats = data;
                })
                .catch(error => {
                    console.error('Error fetching stats:', error);
                });
        },

        // Get Badge classes
        getConfigTypeBadgeClass(configType) {
            switch (configType) {
                case 'XRAY': return 'bg-blue';
                case 'HYSTERIA': return 'bg-purple';
                case 'HYSTERIA2': return 'bg-green';
                default: return 'bg-gray';
            }
        },

        // Config size calculation
        getConfigSize(config) {
            if (!config) return '0 B';
            const size = new Blob([config]).size;
            if (size < 1024) return size + ' B';
            if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
            return (size / (1024 * 1024)).toFixed(1) + ' MB';
        },

        // Get server name
        getServerName(serverId) {
            const server = this.servers.find(s => s.id === serverId);
            return server ? server.name : serverId;
        },

        // Format JSON for display
        formatJsonForDisplay(jsonString) {
            try {
                const obj = JSON.parse(jsonString);
                return JSON.stringify(obj, null, 2);
            } catch (error) {
                return jsonString;
            }
        },

        // View configuration
        viewConfig(config) {
            this.editedItem = {
                id: config.id,
                serverId: config.serverId,
                config: config.config,
                configType: config.configType,
                path: config.path || ''
            };
            this.configViewMode = 'formatted';
            this.configCollapsed = false;
            this.viewConfigModal = new Modal(document.getElementById('viewConfigModal'));
            this.viewConfigModal.show();
        },

        // Toggle config view mode
        toggleConfigView() {
            this.configViewMode = this.configViewMode === 'formatted' ? 'raw' : 'formatted';
        },

        // Toggle config collapse
        toggleConfigCollapse() {
            this.configCollapsed = !this.configCollapsed;
        },

        // Copy config to clipboard
        async copyConfig(config) {
            try {
                await navigator.clipboard.writeText(config.config);
                ToastUtils.show('Success', '配置已复制到剪贴板', 'success');
            } catch (error) {
                console.error('复制失败:', error);
                ToastUtils.show('Error', '复制失败', 'danger');
            }
        },

        // Copy config content from view modal
        async copyConfigContent() {
            try {
                const content = this.configViewMode === 'formatted' 
                    ? this.formatJsonForDisplay(this.editedItem.config)
                    : this.editedItem.config;
                await navigator.clipboard.writeText(content);
                ToastUtils.show('Success', '配置内容已复制到剪贴板', 'success');
            } catch (error) {
                console.error('复制失败:', error);
                ToastUtils.show('Error', '复制失败', 'danger');
            }
        },

        // Download config
        downloadConfig() {
            try {
                const content = this.configViewMode === 'formatted' 
                    ? this.formatJsonForDisplay(this.editedItem.config)
                    : this.editedItem.config;
                
                const blob = new Blob([content], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `config_${this.editedItem.id}_${this.editedItem.configType.toLowerCase()}.json`;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
                
                ToastUtils.show('Success', '配置已下载', 'success');
            } catch (error) {
                console.error('下载失败:', error);
                ToastUtils.show('Error', '下载失败', 'danger');
            }
        },

        // JSON formatting and validation
        formatJson() {
            try {
                const obj = JSON.parse(this.editedItem.config);
                this.editedItem.config = JSON.stringify(obj, null, 2);
                ToastUtils.show('Success', 'JSON格式化成功', 'success');
            } catch (error) {
                ToastUtils.show('Error', 'JSON格式错误，无法格式化', 'danger');
            }
        },

        validateJson() {
            try {
                JSON.parse(this.editedItem.config);
                ToastUtils.show('Success', 'JSON格式正确', 'success');
            } catch (error) {
                ToastUtils.show('Error', 'JSON格式错误: ' + error.message, 'danger');
            }
        },

        clearConfig() {
            if (confirm('确定要清空配置内容吗？')) {
                this.editedItem.config = '';
            }
        },

        // Form validation and preparation
        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            // Check server
            if (!this.newItem.serverId) {
                this.validationErrors.serverId = '请选择服务器';
                isValid = false;
            }

            // Check config type
            if (!this.newItem.configType) {
                this.validationErrors.configType = '请选择配置类型';
                isValid = false;
            }

            // Check config content
            if (!this.newItem.config || this.newItem.config.trim() === '') {
                this.validationErrors.config = '请输入配置内容';
                isValid = false;
            } else {
                try {
                    JSON.parse(this.newItem.config);
                } catch (error) {
                    this.validationErrors.config = '配置内容不是有效的JSON格式';
                    isValid = false;
                }
            }

            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            // Similar validation as create form
            if (!this.editedItem.serverId) {
                this.validationErrors.serverId = '请选择服务器';
                isValid = false;
            }

            if (!this.editedItem.configType) {
                this.validationErrors.configType = '请选择配置类型';
                isValid = false;
            }

            if (!this.editedItem.config || this.editedItem.config.trim() === '') {
                this.validationErrors.config = '请输入配置内容';
                isValid = false;
            } else {
                try {
                    JSON.parse(this.editedItem.config);
                } catch (error) {
                    this.validationErrors.config = '配置内容不是有效的JSON格式';
                    isValid = false;
                }
            }

            return isValid;
        },

        prepareCreateData() {
            return {
                serverId: this.newItem.serverId,
                config: this.newItem.config,
                configType: this.newItem.configType,
                path: this.newItem.path || null
            };
        },

        prepareUpdateData() {
            return {
                id: this.editedItem.id,
                serverId: this.editedItem.serverId,
                config: this.editedItem.config,
                configType: this.editedItem.configType,
                path: this.editedItem.path || null
            };
        },

        resetCreateForm() {
            this.newItem = {
                serverId: this.servers.length > 0 ? this.servers[0].id : '',
                config: '',
                configType: '',
                path: ''
            };
        },

        prepareEditForm(config) {
            return {
                id: config.id,
                serverId: config.serverId,
                config: config.config,
                configType: config.configType,
                path: config.path || ''
            };
        },

        // URLs for API calls
        getApiUrl() {
            return '/api/admin/server-configs';
        },

        // Enhanced upload with progress
        async uploadConfigToServer(config) {
            if (!config.id) return;

            if (!confirm(`确定要上传配置到服务器 ${this.getServerName(config.serverId)} 吗？`)) {
                return;
            }

            this.uploading = true;
            this.uploadProgress = 0;
            this.uploadProgressMessage = '正在连接服务器...';
            
            // Show progress modal
            const progressModal = new Modal(document.getElementById('uploadProgressModal'));
            progressModal.show();

            try {
                const response = await fetch(`/api/admin/server-configs/${config.id}/upload`, {
                    method: 'POST'
                });

                if (response.ok) {
                    this.uploadProgress = 100;
                    this.uploadProgressMessage = '上传成功！';
                    setTimeout(() => {
                        progressModal.hide();
                        ToastUtils.show('Success', '配置上传成功', 'success');
                    }, 1000);
                } else {
                    const error = await response.json();
                    this.uploadProgressMessage = '上传失败: ' + (error.message || '未知错误');
                    setTimeout(() => {
                        progressModal.hide();
                        ToastUtils.show('Error', '上传失败: ' + (error.message || '未知错误'), 'danger');
                    }, 2000);
                }
            } catch (error) {
                console.error('上传配置失败:', error);
                this.uploadProgressMessage = '上传失败: 网络错误';
                setTimeout(() => {
                    progressModal.hide();
                    ToastUtils.show('Error', '上传配置失败', 'danger');
                }, 2000);
            } finally {
                this.uploading = false;
            }
        },

        // Override success callbacks to refresh stats
        onCreateSuccess() {
            this.fetchStats();
        },

        onUpdateSuccess() {
            this.fetchStats();
        },

        onDeleteSuccess() {
            this.fetchStats();
        }
    }
});

// Initialize the table
configTable.createApp('#server-configs-app');