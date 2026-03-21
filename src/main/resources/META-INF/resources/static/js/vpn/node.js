import { DataTable } from '/static/js/common/data-table.js';
import { createSearchDropdown } from '/static/js/common/search-dropdown.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';

const nodeTable = new DataTable({
    data: {
        entityName: 'nodes',
        modalIdPrefix: 'node-',
        filters: {
            serverId: '',
            type: '',
            coreType: '',
            protocol: '',
            disabled: '',
            deployed: ''
        },
        servers: [],
        landingNodes: [],
        nodeTypes: [],
        coreTypes: [],
        protocolTypes: [],
        availableTags: [],
        serverFilterSearch: null,
        stats: {
            total: 0,
            active: 0,
            proxy: 0,
            landing: 0
        },
        newItem: {
            serverId: '',
            backupServerId: '',
            port: null,
            coreType: 'xray',
            protocol: 'vless',
            type: 0,
            level: 0,
            disabled: false,
            name: '',
            remark: '',
            inbound: null,
            outId: null,
            rule: null,
            tagIds: []
        },
        portCheckMessage: '',
        editPortCheckMessage: '',
        newNodeInbound: {
            protocol: 'vless'
        },
        newNodeInboundJson: '',
        newNodeRuleJson: '',
        editedNodeInbound: {
            protocol: 'vless'
        },
        editedNodeInboundJson: '',
        editedNodeOutbound: '',
        editedNodeRuleJson: '',
        viewConfigModal: null,
        batchDeployModal: null,
        selectedNodeIds: [],
        batchDeploying: false
    },
    methods: {
        initialize() {
            this.fetchServers();
            this.fetchLandingNodes();
            this.fetchNodeTypes();
            this.fetchCoreTypes();
            this.fetchProtocolTypes();
            this.fetchAvailableTags();
            this.initializeSearchComponents();
        },

        initializeSearchComponents() {
            this.serverFilterSearch = createSearchDropdown({
                placeholder: '全部服务器',
                apiUrl: '/api/admin/servers',
                minQueryLength: 0,
                formatItem: (item) => ({
                    id: item.id,
                    name: item.name ? `${item.ip} (${item.name})` : item.ip,
                    data: item
                }),
                onSelect: (item) => {
                    this.filters.serverId = String(item.id);
                    this.onFilterChange();
                },
                onChange: (text, item) => {
                    if (!text && !item && this.filters.serverId) {
                        this.filters.serverId = '';
                        this.onFilterChange();
                    }
                }
            });

            setTimeout(() => {
                this.serverFilterSearch.bindToDOM('nodeServerFilter');
            }, 100);
        },

        fetchServers() {
            fetch('/api/admin/nodes/servers')
                .then(response => response.json())
                .then(data => {
                    this.servers = data;
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
                    console.error('Error fetching landing nodes:', error);
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

        fetchCoreTypes() {
            fetch('/api/admin/nodes/core-types')
                .then(response => response.json())
                .then(data => {
                    this.coreTypes = data;
                })
                .catch(error => {
                    console.error('Error fetching core types:', error);
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

        fetchAvailableTags() {
            fetch('/api/admin/tags/enabled')
                .then(response => response.json())
                .then(data => {
                    this.availableTags = data;
                })
                .catch(error => {
                    console.error('Error fetching available tags:', error);
                });
        },

        onFilterChange() {
            const availableProtocols = this.getFilterProtocolOptions();
            if (this.filters.protocol && !availableProtocols.some(protocol => protocol.value === this.filters.protocol)) {
                this.filters.protocol = '';
            }
            this.currentPage = 1;
            this.fetchRecords();
        },

        getFilterProtocolOptions() {
            return this.protocolTypes.filter(protocol => {
                const matchedType = this.filters.type === '' || String(protocol.type) === String(this.filters.type);
                const matchedCore = this.filters.coreType === ''
                    || !protocol.coreTypes
                    || protocol.coreTypes.includes(this.filters.coreType);
                return matchedType && matchedCore;
            });
        },

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
            this.syncProtocolSelection(this.newItem, false);
        },

        onCoreTypeChange() {
            this.syncProtocolSelection(this.newItem, false);
        },

        onEditTypeChange() {
            if (this.editedItem.type === 1) {
                this.editedItem.outId = 0;
            }
            this.syncProtocolSelection(this.editedItem, true);
        },

        onEditCoreTypeChange() {
            this.syncProtocolSelection(this.editedItem, true);
        },

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

        onProtocolChange() {
            const coreType = this.getDefaultInboundCoreType(this.newItem);
            fetch(`/api/admin/nodes/default-inbound?protocol=${this.newItem.protocol}&serverId=${encodeURIComponent(this.newItem.serverId || '')}&coreType=${encodeURIComponent(coreType)}`)
                .then(response => response.json())
                .then(data => {
                    this.newNodeInbound = data.config;
                    this.newNodeInboundJson = JSON.stringify(data.config, null, 2);
                })
                .catch(error => {
                    console.error('Error getting default inbound config:', error);
                });
        },

        onEditProtocolChange() {
            const coreType = this.getDefaultInboundCoreType(this.editedItem);
            fetch(`/api/admin/nodes/default-inbound?protocol=${this.editedItem.protocol}&serverId=${encodeURIComponent(this.editedItem.serverId || '')}&coreType=${encodeURIComponent(coreType)}`)
                .then(response => response.json())
                .then(data => {
                    this.editedNodeInbound = data.config;
                    this.editedNodeInboundJson = JSON.stringify(data.config, null, 2);
                })
                .catch(error => {
                    console.error('Error getting default inbound config:', error);
                });
        },

        getDefaultInboundCoreType(item) {
            return item && item.coreType ? item.coreType : 'xray';
        },

        getAvailableProtocols(item) {
            const coreType = this.getDefaultInboundCoreType(item);
            return this.protocolTypes.filter(protocol => {
                const matchedType = protocol.type === item.type;
                const matchedCore = !protocol.coreTypes || protocol.coreTypes.includes(coreType);
                return matchedType && matchedCore;
            });
        },

        syncProtocolSelection(item, isEdit) {
            if (!item) {
                return;
            }

            const protocols = this.getAvailableProtocols(item);
            if (protocols.length === 0) {
                item.protocol = '';
                if (isEdit) {
                    this.editedNodeInbound = {};
                    this.editedNodeInboundJson = '{}';
                } else {
                    this.newNodeInbound = {};
                    this.newNodeInboundJson = '{}';
                }
                return;
            }

            if (!protocols.some(protocol => protocol.value === item.protocol)) {
                item.protocol = protocols[0].value;
            }

            if (isEdit) {
                this.onEditProtocolChange();
            } else {
                this.onProtocolChange();
            }
        },

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

        viewNodeConfig(node) {
            this.editedNodeInboundJson = JSON.stringify(node.inbound || {}, null, 2);
            this.editedNodeRuleJson = JSON.stringify(node.rule || {}, null, 2);
            this.editedNodeOutbound = node.outName + ' (' + node.serverHost + ':' + node.outPort + ')';
            this.viewConfigModal = new Modal(document.getElementById('viewConfigModal'));
            this.viewConfigModal.show();
        },

        validateCreateForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.newItem.serverId) {
                this.validationErrors.serverId = '请选择服务器';
                isValid = false;
            }

            if (!this.newItem.port) {
                this.validationErrors.port = '请输入端口';
                isValid = false;
            } else if (this.newItem.port < 1 || this.newItem.port > 65535) {
                this.validationErrors.port = '端口范围应为 1-65535';
                isValid = false;
            }

            if (!this.newItem.name) {
                this.validationErrors.name = '请填写节点名称';
                isValid = false;
            }

            if (!this.newItem.no) {
                this.validationErrors.no = '请填写节点编号';
                isValid = false;
            }

            if (this.newItem.type === null || this.newItem.type === undefined) {
                this.validationErrors.type = '请选择节点类型';
                isValid = false;
            }

            if (!this.newItem.coreType) {
                this.validationErrors.coreType = '请选择内核类型';
                isValid = false;
            }

            if (!this.newItem.protocol || !this.getAvailableProtocols(this.newItem).some(protocol => protocol.value === this.newItem.protocol)) {
                this.validationErrors.protocol = '请选择可用协议';
                isValid = false;
            }

            return isValid;
        },

        validateEditForm() {
            let isValid = true;
            this.validationErrors = {};

            if (!this.editedItem.serverId) {
                this.validationErrors.serverId = '请选择服务器';
                isValid = false;
            }

            if (!this.editedItem.port) {
                this.validationErrors.port = '请输入端口';
                isValid = false;
            } else if (this.editedItem.port < 1 || this.editedItem.port > 65535) {
                this.validationErrors.port = '端口范围应为 1-65535';
                isValid = false;
            }

            if (!this.editedItem.name) {
                this.validationErrors.name = '请填写节点名称';
                isValid = false;
            }

            if (!this.editedItem.no) {
                this.validationErrors.no = '请填写节点编号';
                isValid = false;
            }

            if (this.editedItem.type === null || this.editedItem.type === undefined) {
                this.validationErrors.type = '请选择节点类型';
                isValid = false;
            }

            if (!this.editedItem.coreType) {
                this.validationErrors.coreType = '请选择内核类型';
                isValid = false;
            }

            if (!this.editedItem.protocol || !this.getAvailableProtocols(this.editedItem).some(protocol => protocol.value === this.editedItem.protocol)) {
                this.validationErrors.protocol = '请选择可用协议';
                isValid = false;
            }

            return isValid;
        },

        prepareCreateData() {
            try {
                let inboundConfig = this.newNodeInbound;
                if (this.newNodeInboundJson) {
                    const jsonData = JSON.parse(this.newNodeInboundJson);
                    inboundConfig = { ...inboundConfig, ...jsonData };
                }

                let ruleConfig = null;
                if (this.newNodeRuleJson) {
                    ruleConfig = JSON.parse(this.newNodeRuleJson);
                }

                return {
                    serverId: this.newItem.serverId,
                    backupServerId: this.newItem.backupServerId || null,
                    port: this.newItem.port,
                    coreType: this.newItem.coreType,
                    protocol: this.newItem.protocol,
                    type: this.newItem.type,
                    level: this.newItem.level || 0,
                    disabled: this.newItem.disabled ? 1 : 0,
                    name: this.newItem.name || null,
                    no: this.newItem.no || null,
                    remark: this.newItem.remark || null,
                    inbound: inboundConfig,
                    outId: this.newItem.outId || null,
                    rule: ruleConfig,
                    tagIds: this.newItem.tagIds || []
                };
            } catch (e) {
                console.error('Error parsing JSON:', e);
                ToastUtils.show('Error', 'JSON 格式错误: ' + e.message, 'danger');
                throw e;
            }
        },

        prepareUpdateData() {
            try {
                if (this.editedNodeInboundJson) {
                    this.editedNodeInbound = JSON.parse(this.editedNodeInboundJson);
                }

                let ruleConfig = null;
                if (this.editedNodeRuleJson) {
                    ruleConfig = JSON.parse(this.editedNodeRuleJson);
                }

                return {
                    id: this.editedItem.id,
                    serverId: this.editedItem.serverId,
                    backupServerId: this.editedItem.backupServerId === 0 ? null : this.editedItem.backupServerId,
                    port: this.editedItem.port,
                    coreType: this.editedItem.coreType,
                    protocol: this.editedItem.protocol,
                    type: this.editedItem.type,
                    level: this.editedItem.level || 0,
                    disabled: this.editedItem.disabled,
                    name: this.editedItem.name || null,
                    no: this.editedItem.no || null,
                    remark: this.editedItem.remark || null,
                    inbound: this.editedNodeInbound,
                    outId: this.editedItem.outId === 0 ? null : this.editedItem.outId,
                    rule: ruleConfig,
                    tagIds: this.editedItem.tagIds || []
                };
            } catch (e) {
                console.error('Error parsing JSON:', e);
                ToastUtils.show('Error', 'JSON 格式错误: ' + e.message, 'danger');
                throw e;
            }
        },

        resetCreateForm() {
            this.fetchLandingNodes();
            this.newItem = {
                serverId: this.servers.length > 0 ? this.servers[0].id : '',
                backupServerId: '',
                port: null,
                coreType: 'xray',
                protocol: 'vless',
                type: 0,
                level: 0,
                disabled: false,
                name: null,
                no: null,
                remark: '',
                inbound: null,
                outId: null,
                rule: null,
                tagIds: []
            };

            this.newNodeInbound = {
                protocol: 'vless'
            };
            this.onProtocolChange();

            this.newNodeRuleJson = '{}';
            this.portCheckMessage = '';
        },

        loadNodeTags(nodeId) {
            fetch(`/api/admin/tags/nodes/${nodeId}`)
                .then(response => response.json())
                .then(data => {
                    this.editedItem.tagIds = data.map(tag => tag.id);
                })
                .catch(error => {
                    console.error('Error loading node tags:', error);
                    this.editedItem.tagIds = [];
                });
        },

        prepareEditForm(node) {
            this.fetchLandingNodes();
            this.editedNodeInbound = node.inbound || { protocol: 'vless' };
            this.editedNodeInboundJson = JSON.stringify(node.inbound || {}, null, 2);
            this.editedNodeRuleJson = JSON.stringify(node.rule || {}, null, 2);

            this.editPortCheckMessage = '';
            this.loadNodeTags(node.id);

            return {
                id: node.id,
                serverId: node.serverId,
                backupServerId: !node.backupServerId ? 0 : node.backupServerId,
                port: node.port,
                coreType: node.coreType || 'xray',
                protocol: node.protocol,
                type: node.type,
                level: node.level || 0,
                disabled: node.disabled,
                name: node.name || null,
                no: node.no || null,
                remark: node.remark || '',
                inbound: node.inbound,
                outId: !node.outId ? 0 : node.outId,
                rule: node.rule,
                tagIds: []
            };
        },

        getApiUrl() {
            return '/api/admin/nodes';
        },

        getToggleStatusUrl(item, action) {
            return `/api/admin/nodes/${item.id}/${action}`;
        },

        updateItemStatus(item, data) {
            const index = this.records.findIndex(r => r.id === item.id);
            if (index !== -1) {
                this.records[index].disabled = data.disabled;
                this.records[index].deployed = data.deployed;
            }
        },

        copyNode(node) {
            if (!node.id) return;

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
                .then(() => {
                    this.fetchRecords();
                    ToastUtils.show('Success', '复制节点成功', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', '复制节点失败', 'danger');
                });
        },

        openBatchDeployModal() {
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
                body: '[]'
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

                    this.fetchRecords();
                })
                .catch(error => {
                    console.error('Error:', error);
                    this.batchDeploying = false;
                    ToastUtils.show('Error', '批量部署失败', 'danger');
                });
        },

        deployNode(node, forcibly = false) {
            ToastUtils.show('Info', '正在部署节点...', 'info');

            fetch(`/api/admin/nodes/${node.id}/${forcibly ? 'deployForcibly' : 'deploy'}`, {
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

        getDeploymentStatusBadgeClass(deployed) {
            return deployed === 1 ? 'text-bg-success' : 'text-bg-warning';
        }
    }
});

nodeTable.createApp('#app');
