import { DataTable } from '/static/js/common/data-table.js';
import { createSearchDropdown } from '/static/js/common/search-dropdown.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';

const DEFAULT_NODE_FILTERS = Object.freeze({
    serverId: '',
    type: '',
    coreType: '',
    protocol: '',
    disabled: '',
    deployed: ''
});

const nodeTable = new DataTable({
    data: {
        entityName: 'nodes',
        modalIdPrefix: 'node-',
        filters: { ...DEFAULT_NODE_FILTERS },
        servers: [],
        landingNodes: [],
        nodeTypes: [],
        coreTypes: [],
        protocolTypes: [],
        availableTags: [],
        serverFilterSearch: null,
        mobileServerFilterSearch: null,
        stats: {
            total: 0,
            active: 0,
            proxy: 0,
            landing: 0
        },
        newItem: {
            serverId: '',
            backupServerId: '',
            accessHostId: '',
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
        coreSwitchModal: null,
        selectedNodeIds: [],
        coreSwitchTarget: 'sing-box',
        coreSwitchRedeploy: true,
        switchingCore: false,
        batchDeploying: false,
        deployingNodeIds: []
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

        afterFetch() {
            const currentIds = new Set(this.records.map(record => record.id));
            this.selectedNodeIds = this.selectedNodeIds.filter(id => currentIds.has(id));
        },

        initializeSearchComponents() {
            const buildServerFilterSearch = () => createSearchDropdown({
                placeholder: '全部服务器',
                apiUrl: '/api/admin/servers',
                minQueryLength: 0,
                formatItem: (item) => ({
                    id: item.id,
                    name: this.formatServerDisplayLabel(item),
                    data: item
                }),
                onSelect: (item) => {
                    this.filters.serverId = String(item.id);
                    this.syncServerFilterSearches(item);
                    this.onFilterChange();
                },
                onChange: (text, item) => {
                    if (!text && !item && this.filters.serverId) {
                        this.filters.serverId = '';
                        this.syncServerFilterSearches();
                        this.onFilterChange();
                    }
                }
            });

            this.serverFilterSearch = buildServerFilterSearch();
            this.mobileServerFilterSearch = buildServerFilterSearch();

            setTimeout(() => {
                if (this.serverFilterSearch) {
                    this.serverFilterSearch.bindToDOM('nodeServerFilter');
                }
                if (this.mobileServerFilterSearch) {
                    this.mobileServerFilterSearch.bindToDOM('nodeMobileServerFilter');
                }
            }, 100);
        },

        formatServerDisplayLabel(server) {
            if (!server) {
                return '';
            }

            if (server.data) {
                return this.formatServerDisplayLabel(server.data);
            }

            return server.name ? `${server.ip} (${server.name})` : (server.ip || '');
        },

        getServerHosts(serverId) {
            const server = this.servers.find(item => String(item.id) === String(serverId));
            if (!server || !Array.isArray(server.hosts)) {
                return [];
            }
            return server.hosts;
        },

        syncAccessHostSelection(item) {
            if (!item || !item.serverId) {
                item.accessHostId = '';
                return;
            }

            const availableHosts = this.getServerHosts(item.serverId);
            if (!availableHosts.some(host => String(host.id || '') === String(item.accessHostId))) {
                item.accessHostId = '';
            }
        },

        syncServerFilterSearches(serverItem = null) {
            const resolvedItem = serverItem || this.servers.find(item => String(item.id) === String(this.filters.serverId)) || null;
            const text = this.formatServerDisplayLabel(resolvedItem);

            [this.serverFilterSearch, this.mobileServerFilterSearch].forEach(search => {
                if (!search || typeof search.setValue !== 'function') {
                    return;
                }
                search.setValue(text, resolvedItem);
            });
        },

        fetchServers() {
            fetch('/api/admin/nodes/servers')
                .then(response => response.json())
                .then(data => {
                    this.servers = data;
                    this.syncServerFilterSearches();
                    if (this.servers.length > 0 && !this.newItem.serverId) {
                        this.newItem.serverId = this.servers[0].id;
                    }
                    this.syncAccessHostSelection(this.newItem);
                    if (this.editedItem) {
                        this.syncAccessHostSelection(this.editedItem);
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

        getOptionLabel(options, value) {
            return options.find(option => String(option.value) === String(value))?.label || value;
        },

        getServerFilterLabel() {
            if (!this.filters.serverId) {
                return '';
            }

            const server = this.servers.find(item => String(item.id) === String(this.filters.serverId));
            if (!server) {
                return this.filters.serverId;
            }

            return this.formatServerDisplayLabel(server);
        },

        getFilterDefinitions() {
            return [
                {
                    key: 'serverId',
                    label: '服务器',
                    isActive: value => value !== '',
                    getValueLabel: () => this.getServerFilterLabel()
                },
                {
                    key: 'type',
                    label: '类型',
                    isActive: value => value !== '',
                    getValueLabel: value => this.getOptionLabel(this.nodeTypes, value)
                },
                {
                    key: 'coreType',
                    label: '内核',
                    isActive: value => value !== '',
                    getValueLabel: value => this.getOptionLabel(this.coreTypes, value)
                },
                {
                    key: 'protocol',
                    label: '协议',
                    isActive: value => value !== '',
                    getValueLabel: value => this.getOptionLabel(this.getFilterProtocolOptions(), value)
                },
                {
                    key: 'disabled',
                    label: '状态',
                    isActive: value => value !== '',
                    getValueLabel: value => value === 'true' ? '已禁用' : '已启用'
                },
                {
                    key: 'deployed',
                    label: '部署',
                    isActive: value => value !== '',
                    getValueLabel: value => value === 'true' ? '已部署' : '未部署'
                }
            ];
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

        getTypeIconClass(type) {
            switch (type) {
                case 0: return 'ti-world-code';
                case 1: return 'ti-vector-triangle';
                default: return 'ti-point';
            }
        },

        getStatusBadgeClass(disabled) {
            return disabled === 0 ? 'bg-success-lt' : 'bg-danger-lt';
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
            this.syncAccessHostSelection(this.newItem);
            this.checkPortAvailability();
        },

        onEditServerChange() {
            this.syncAccessHostSelection(this.editedItem);
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
            fetch(`/api/admin/nodes/default-inbound?protocol=${this.newItem.protocol}&serverId=${encodeURIComponent(this.newItem.serverId || '')}&accessHostId=${encodeURIComponent(this.newItem.accessHostId || '')}&coreType=${encodeURIComponent(coreType)}`)
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
            fetch(`/api/admin/nodes/default-inbound?protocol=${this.editedItem.protocol}&serverId=${encodeURIComponent(this.editedItem.serverId || '')}&accessHostId=${encodeURIComponent(this.editedItem.accessHostId || '')}&coreType=${encodeURIComponent(coreType)}`)
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
                    accessHostId: this.newItem.accessHostId || null,
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
                    accessHostId: this.editedItem.accessHostId || null,
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
                accessHostId: '',
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
                accessHostId: node.accessHostId || '',
                port: node.port,
                coreType: node.coreType,
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

        openCoreSwitchModal() {
            if (!this.selectedNodeIds.length) {
                ToastUtils.show('Warning', '请先勾选要切换的节点', 'warning');
                return;
            }

            const selectedNodes = this.records.filter(node => this.selectedNodeIds.includes(node.id));
            const hasXray = selectedNodes.some(node => node.coreType === 'xray');
            const hasSingBox = selectedNodes.some(node => node.coreType === 'sing-box');
            if (hasXray && hasSingBox) {
                ToastUtils.show('Warning', '所选节点必须是同一种原内核', 'warning');
                return;
            }

            const sourceCoreType = hasXray ? 'xray' : 'sing-box';
            this.coreSwitchTarget = sourceCoreType === 'xray' ? 'sing-box' : 'xray';
            const unsupportedNodes = selectedNodes.filter(node =>
                !this.getAvailableProtocols({ type: node.type, coreType: this.coreSwitchTarget })
                    .some(protocol => protocol.value === node.protocol)
            );
            if (unsupportedNodes.length > 0) {
                const labels = unsupportedNodes
                    .map(node => `${node.name || `节点#${node.id}`}(${node.protocol})`)
                    .join('、');
                ToastUtils.show('Warning', `以下节点协议不支持切换到 ${this.coreSwitchTarget}: ${labels}`, 'warning');
                return;
            }
            this.coreSwitchRedeploy = true;
            this.coreSwitchModal = new Modal(document.getElementById('node-coreSwitchModal'));
            this.coreSwitchModal.show();
        },

        isNodeSelected(nodeId) {
            return this.selectedNodeIds.includes(nodeId);
        },

        toggleNodeSelection(nodeId, checked) {
            if (checked) {
                if (!this.selectedNodeIds.includes(nodeId)) {
                    this.selectedNodeIds.push(nodeId);
                }
                return;
            }
            this.selectedNodeIds = this.selectedNodeIds.filter(id => id !== nodeId);
        },

        isAllCurrentPageSelected() {
            return this.records.length > 0 && this.records.every(node => this.selectedNodeIds.includes(node.id));
        },

        toggleSelectAllCurrentPage(checked) {
            if (checked) {
                const merged = new Set([...this.selectedNodeIds, ...this.records.map(node => node.id)]);
                this.selectedNodeIds = Array.from(merged);
                return;
            }
            const currentIds = new Set(this.records.map(node => node.id));
            this.selectedNodeIds = this.selectedNodeIds.filter(id => !currentIds.has(id));
        },

        switchSelectedNodesCore() {
            if (!this.selectedNodeIds.length) {
                ToastUtils.show('Warning', '请先勾选要切换的节点', 'warning');
                return;
            }

            this.switchingCore = true;
            fetch('/api/admin/nodes/switch-core', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    nodeIds: this.selectedNodeIds,
                    targetCoreType: this.coreSwitchTarget,
                    redeploy: this.coreSwitchRedeploy
                })
            })
                .then(async response => {
                    if (!response.ok) {
                        const error = await response.json().catch(() => ({ message: '切换内核失败' }));
                        throw new Error(error.message || '切换内核失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.switchingCore = false;
                    this.coreSwitchModal.hide();
                    this.selectedNodeIds = [];
                    this.fetchRecords();

                    const deploymentSummary = data.redeployed
                        ? `，重部署 ${data.deploymentResults ? data.deploymentResults.filter(result => result.success).length : 0} 项`
                        : '';
                    ToastUtils.show(
                        'Success',
                        `已切换 ${data.switchedCount} 个节点，跳过 ${data.unchangedCount} 个节点${deploymentSummary}`,
                        'success'
                    );
                })
                .catch(error => {
                    console.error('Error:', error);
                    this.switchingCore = false;
                    ToastUtils.show('Error', error.message || '切换内核失败', 'danger');
                });
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
            if (this.deployingNodeIds.includes(node.id)) {
                return;
            }

            this.deployingNodeIds.push(node.id);
            const loadingToast = ToastUtils.loading(
                forcibly ? '重新部署中' : '部署中',
                `${node.name || `节点 #${node.id}`} 正在部署，请稍候...`
            );

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
                })
                .finally(() => {
                    loadingToast.hide();
                    this.deployingNodeIds = this.deployingNodeIds.filter(id => id !== node.id);
                });
        },

        isNodeDeploying(nodeId) {
            return this.deployingNodeIds.includes(nodeId);
        },

        getDeploymentStatusBadgeClass(deployed) {
            return deployed === 1 ? 'bg-success-lt' : 'bg-warning-lt';
        },

        ...createResponsiveFilterMethods({
            createDefaultFilters: () => ({ ...DEFAULT_NODE_FILTERS }),
            applyFilters() {
                this.onFilterChange();
            },
            onReset() {
                [this.serverFilterSearch, this.mobileServerFilterSearch].forEach(search => {
                    if (search && typeof search.clear === 'function') {
                        search.clear();
                    }
                });
            },
            onClear(key) {
                if (key === 'serverId') {
                    [this.serverFilterSearch, this.mobileServerFilterSearch].forEach(search => {
                        if (search && typeof search.clear === 'function') {
                            search.clear();
                        }
                    });
                }
            },
            getActiveTags() {
                const tags = this.searchQuery ? [{
                    key: 'search',
                    label: '搜索',
                    value: this.searchQuery
                }] : [];

                this.getFilterDefinitions().forEach(definition => {
                    const value = this.filters[definition.key];
                    if (!definition.isActive(value)) {
                        return;
                    }

                    tags.push({
                        key: definition.key,
                        label: definition.label,
                        value: definition.getValueLabel(value)
                    });
                });

                return tags;
            }
        })
    }
});

nodeTable.createApp('#app');
