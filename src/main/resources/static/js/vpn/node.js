
import { DataTable } from '/static/js/common/data-table.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';

const nodeTable = new DataTable({
    data: {
        entityName: 'nodes',
        modalIdPrefix: 'node-',
        filters: {
            serverId: '',
            type: '',
            status: ''
        },
        servers: [],
        landingNodes: [],
        nodeTypes: [],
        protocolTypes: [],
        stats: {
            total: 0,
            active: 0,
            proxy: 0,
            landing: 0
        },
        // Form data
        newItem: {
            serverId: '',
            port: null,
            type: 0, // 默认为代理节点
            level: 0,
            disabled: false,
            name: '',
            remark: '',
            inbound: null,
            outId: null,
            rule: null
        },
        // Additional data for node management
        portCheckMessage: '',
        editPortCheckMessage: '',
        newNodeInbound: {
            protocol: 'VLESS'
        },
        newNodeInboundJson: '',
        newNodeRuleJson: '',
        editedNodeInbound: {
            protocol: 'VLESS'
        },
        editedNodeInboundJson: '',
        editedNodeOutbound: '',
        editedNodeRuleJson: '',
        // For viewing configs
        viewConfigModal: null,
        batchDeployModal: null,
        selectedNodeIds: [],
        batchDeploying: false
    },
    methods: {
        // Initialize additional data
        initialize() {
            this.fetchServers();
            this.fetchLandingNodes();
            this.fetchNodeTypes();
            this.fetchProtocolTypes();
        },

        // Fetch data methods
        fetchServers() {
            fetch('/api/admin/nodes/servers')
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

        fetchLandingNodes() {
            fetch('/api/admin/nodes/landing')
                .then(response => response.json())
                .then(data => {
                    this.landingNodes = data;
                })
                .catch(error => {
                    console.error('Error fetching servers:', error);
                });
        },

        fetchNodeTypes() {
            fetch('/api/admin/nodes/types')
                .then(response => response.json())
                .then(data => {
                    this.nodeTypes = data;
                })
                .catch(error => {
                    console.error('Error fetching node types:', error);
                });
        },

        fetchProtocolTypes() {
            fetch('/api/admin/nodes/protocols')
                .then(response => response.json())
                .then(data => {
                    this.protocolTypes = data;
                })
                .catch(error => {
                    console.error('Error fetching protocol types:', error);
                });
        },

        // Get Badge classes
        getTypeBadgeClass(type) {
            switch (type) {
                case 0: return 'text-bg-blue';
                case 1: return 'text-bg-purple';
                default: return 'text-bg-secondary';
            }
        },

        getStatusBadgeClass(disabled) {
            return disabled === 0 ? 'text-bg-success' : 'text-bg-danger';
        },

        onTypeChange() {
            if (this.newItem.type === 1) {
                this.newItem.outId = null;
            }
        },

        // Server and port related methods
        onServerChange() {
            this.checkPortAvailability();
        },

        onEditServerChange() {
            this.checkEditPortAvailability();
        },

        checkPortAvailability() {
            if (!this.newItem.serverId || !this.newItem.port) {
                this.portCheckMessage = '';
                return;
            }

            fetch(`/api/admin/nodes/check-port?serverId=${this.newItem.serverId}&port=${this.newItem.port}`)
                .then(response => response.json())
                .then(data => {
                    if (data.available) {
                        this.portCheckMessage = '端口可用';
                    } else {
                        this.portCheckMessage = '端口已被占用';
                    }
                })
                .catch(error => {
                    console.error('Error checking port:', error);
                    this.portCheckMessage = '检查端口失败';
                });
        },

        checkEditPortAvailability() {
            if (!this.editedItem.serverId || !this.editedItem.port || !this.editedItem.id) {
                this.editPortCheckMessage = '';
                return;
            }

            fetch(`/api/admin/nodes/check-port?serverId=${this.editedItem.serverId}&port=${this.editedItem.port}&nodeId=${this.editedItem.id}`)
                .then(response => response.json())
                .then(data => {
                    if (data.available) {
                        this.editPortCheckMessage = '端口可用';
                    } else {
                        this.editPortCheckMessage = '端口已被占用';
                    }
                })
                .catch(error => {
                    console.error('Error checking port:', error);
                    this.editPortCheckMessage = '检查端口失败';
                });
        },

        getAvailablePort() {
            if (!this.newItem.serverId) {
                ToastUtils.show('Warning', '请先选择服务器', 'warning');
                return;
            }

            fetch(`/api/admin/nodes/available-port?serverId=${this.newItem.serverId}`)
                .then(response => response.json())
                .then(data => {
                    this.newItem.port = data.port;
                    this.checkPortAvailability();
                })
                .catch(error => {
                    console.error('Error getting available port:', error);
                    ToastUtils.show('Error', '获取可用端口失败', 'danger');
                });
        },

        getEditAvailablePort() {
            if (!this.editedItem.serverId) {
                ToastUtils.show('Warning', '请先选择服务器', 'warning');
                return;
            }

            fetch(`/api/admin/nodes/available-port?serverId=${this.editedItem.serverId}`)
                .then(response => response.json())
                .then(data => {
                    this.editedItem.port = data.port;
                    this.checkEditPortAvailability();
                })
                .catch(error => {
                    console.error('Error getting available port:', error);
                    ToastUtils.show('Error', '获取可用端口失败', 'danger');
                });
        },

        // Protocol related methods
        onProtocolChange() {
            // 获取默认配置
            fetch(`/api/admin/nodes/default-inbound?protocol=${this.newNodeInbound.protocol}`)
                .then(response => response.json())
                .then(data => {
                    this.newNodeInbound = data;
                    this.newNodeInboundJson = JSON.stringify(data, null, 2);
                })
                .catch(error => {
                    console.error('Error getting default inbound config:', error);
                });
        },

        onEditProtocolChange() {
            // 获取默认配置
            fetch(`/api/admin/nodes/default-inbound?protocol=${this.editedNodeInbound.protocol}`)
                .then(response => response.json())
                .then(data => {
                    this.editedNodeInbound = data;
                    this.editedNodeInboundJson = JSON.stringify(data, null, 2);
                })
                .catch(error => {
                    console.error('Error getting default inbound config:', error);
                });
        },

        // Generator methods
        generateUuid() {
            this.newNodeInbound.uuid = this.uuidv4();
        },

        generateRandomPassword() {
            const password = this.generateRandomString(16);

            if (this.newNodeInbound.password !== undefined) {
                this.newNodeInbound.password = password;
            }

            if (this.editedNodeInbound.password !== undefined) {
                this.editedNodeInbound.password = password;
            }
        },

        // View configuration
        viewNodeConfig(node) {
            this.editedNodeInboundJson = JSON.stringify(node.inbound || {}, null, 2);
            this.editedNodeRuleJson = JSON.stringify(node.rule || {}, null, 2);
            this.editedNodeOutbound = node.outName + ' (' + node.serverHost + ':' + node.outPort + ')';
            this.viewConfigModal = new Modal(document.getElementById('viewConfigModal'));
            this.viewConfigModal.show();
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

            // Check port
            if (!this.newItem.port) {
                this.validationErrors.port = '请输入端口';
                isValid = false;
            } else if (this.newItem.port < 1 || this.newItem.port > 65535) {
                this.validationErrors.port = '端口范围应为1-65535';
                isValid = false;
            }

            // Check node type
            if (this.newItem.type === null || this.newItem.type === undefined) {
                this.validationErrors.type = '请选择节点类型';
                isValid = false;
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

            if (!this.editedItem.port) {
                this.validationErrors.port = '请输入端口';
                isValid = false;
            } else if (this.editedItem.port < 1 || this.editedItem.port > 65535) {
                this.validationErrors.port = '端口范围应为1-65535';
                isValid = false;
            }

            if (this.editedItem.type === null || this.editedItem.type === undefined) {
                this.validationErrors.type = '请选择节点类型';
                isValid = false;
            }

            return isValid;
        },

        prepareCreateData() {
            try {
                // Merge inbound config with JSON
                let inboundConfig = this.newNodeInbound;
                if (this.newNodeInboundJson) {
                    const jsonData = JSON.parse(this.newNodeInboundJson);
                    inboundConfig = { ...inboundConfig, ...jsonData };
                }

                // Process rule configs
                let ruleConfig = null;

                if (this.newNodeRuleJson && this.newNodeRuleJson.trim() !== '{}') {
                    ruleConfig = JSON.parse(this.newNodeRuleJson);
                }

                // Prepare data for API
                return {
                    serverId: this.newItem.serverId,
                    port: this.newItem.port,
                    type: this.newItem.type,
                    level: this.newItem.level || 0,
                    disabled: this.newItem.disabled ? 1 : 0,
                    name: this.newItem.name || null,
                    remark: this.newItem.remark || null,
                    inbound: inboundConfig,
                    outId: this.newItem.outId || null,
                    rule: ruleConfig
                };
            } catch (e) {
                console.error('Error parsing JSON:', e);
                ToastUtils.show('Error', 'JSON格式错误: ' + e.message, 'danger');
                throw e;
            }
        },

        prepareUpdateData() {
            try {
                // Similar to create data preparation
                let inboundConfig = this.editedNodeInbound;
                if (this.editedNodeInboundJson) {
                    const jsonData = JSON.parse(this.editedNodeInboundJson);
                    inboundConfig = { ...inboundConfig, ...jsonData };
                }

                let ruleConfig = null;

                if (this.editedNodeRuleJson && this.editedNodeRuleJson.trim() !== '{}') {
                    ruleConfig = JSON.parse(this.editedNodeRuleJson);
                }

                return {
                    id: this.editedItem.id,
                    serverId: this.editedItem.serverId,
                    port: this.editedItem.port,
                    type: this.editedItem.type,
                    level: this.editedItem.level || 0,
                    disabled: this.editedItem.disabled,
                    name: this.editedItem.name || null,
                    remark: this.editedItem.remark || null,
                    inbound: inboundConfig,
                    outId: this.editedItem.outId === 0 ? null : this.editedItem.outId,
                    rule: ruleConfig
                };
            } catch (e) {
                console.error('Error parsing JSON:', e);
                ToastUtils.show('Error', 'JSON格式错误: ' + e.message, 'danger');
                throw e;
            }
        },

        resetCreateForm() {
            this.newItem = {
                serverId: this.servers.length > 0 ? this.servers[0].id : '',
                port: null,
                type: 0,
                level: 0,
                disabled: false,
                name: '',
                remark: '',
                inbound: null,
                outId: null,
                rule: null
            };

            // Reset inbound data
            this.newNodeInbound = {
                protocol: 'VLESS'
            };
            this.onProtocolChange(); // Get default configuration

            this.newNodeRuleJson = '{}';
            this.portCheckMessage = '';
        },

        prepareEditForm(node) {
            // Format inbound and rule data
            this.editedNodeInbound = node.inbound || { protocol: 'VLESS' };
            this.editedNodeInboundJson = JSON.stringify(node.inbound || {}, null, 2);
            this.editedNodeRuleJson = JSON.stringify(node.rule || {}, null, 2);

            this.editPortCheckMessage = '';

            // Return basic item data
            return {
                id: node.id,
                serverId: node.serverId,
                port: node.port,
                type: node.type,
                level: node.level || 0,
                disabled: node.disabled,
                name: node.name || '',
                remark: node.remark || '',
                inbound: node.inbound,
                outId: !node.outId ? 0 : node.outId,
                rule: node.rule
            };
        },

        // URLs for API calls
        getApiUrl() {
            return '/api/admin/nodes';
        },

        getToggleStatusUrl(item, enabled) {
            return `/api/admin/nodes/${item.id}/${enabled ? 'enable' : 'disable'}`;
        },

        copyNode(node) {
            if (!node.id) return;

            // Show loading toast
            ToastUtils.show('Info', '正在复制节点...', 'info');

            fetch(`/api/admin/nodes/${node.id}/copy`, {
                method: 'POST'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('复制节点失败');
                    }
                    return response.json();
                })
                .then(data => {
                    // Refresh the node list
                    this.fetchRecords();
                    ToastUtils.show('Success', '复制节点成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '复制节点失败', 'danger');
                });
        },

        openBatchDeployModal() {
            // Get all selected nodes (those with status = 0)
            this.selectedNodeIds = this.records
                .filter(node => node.deployed === 0)
                .map(node => node.id);

            if (this.selectedNodeIds.length === 0) {
                ToastUtils.show('Warning', '没有可部署的节点', 'warning');
                return;
            }

            this.batchDeployModal = new Modal(document.getElementById('batchDeployModal'));
            this.batchDeployModal.show();
        },

        deploySelectedNodes() {
            this.batchDeploying = true;

            fetch('/api/admin/nodes/deploy-batch', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(this.selectedNodeIds)
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('批量部署失败');
                    }
                    return response.json();
                })
                .then(data => {
                    const successCount = data.filter(result => result.success).length;
                    const failureCount = data.length - successCount;

                    this.batchDeploying = false;
                    this.batchDeployModal.hide();

                    if (failureCount === 0) {
                        ToastUtils.show('Success', `成功部署 ${successCount} 个节点`, 'success');
                    } else {
                        ToastUtils.show('Warning', `成功: ${successCount}, 失败: ${failureCount}`, 'warning');
                    }

                    // Refresh the node list
                    this.fetchRecords();
                })
                .catch(error => {
                    console.error('Error:', error);
                    this.batchDeploying = false;
                    ToastUtils.show('Error', '批量部署失败', 'danger');
                });
        },

        deployNode(node) {
            // Show loading toast
            ToastUtils.show('Info', '正在部署节点...', 'info');

            fetch(`/api/admin/nodes/${node.id}/deploy`, {
                method: 'POST'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('部署节点失败');
                    }
                    return response.json();
                })
                .then(data => {
                    if (data.success) {
                        // Refresh the node list
                        this.fetchRecords();
                        ToastUtils.show('Success', data.message, 'success');
                    } else {
                        ToastUtils.show('Error', data.message, 'danger');
                    }
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '部署节点失败', 'danger');
                });
        },

        undeployNode(node) {
            // Show loading toast
            ToastUtils.show('Info', '正在取消部署节点...', 'info');

            fetch(`/api/admin/nodes/${node.id}/undeploy`, {
                method: 'POST'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('取消部署节点失败');
                    }
                    return response.json();
                })
                .then(data => {
                    if (data.success) {
                        // Refresh the node list
                        this.fetchRecords();
                        ToastUtils.show('Success', data.message, 'success');
                    } else {
                        ToastUtils.show('Error', data.message, 'danger');
                    }
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '取消部署节点失败', 'danger');
                });
        },

        getDeploymentStatusBadgeClass(deployed) {
            return deployed === 1 ? 'text-bg-success' : 'text-bg-warning';
        }
    }
});

// Initialize the Vue app
nodeTable.createApp('#nodes-app');