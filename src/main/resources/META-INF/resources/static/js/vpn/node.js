import { DataTable } from '/static/js/common/data-table.js';
import { Modal } from '/static/tabler/js/tabler.esm.min.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';
import { createNodeDeployMethods } from '/static/js/vpn/node-deploy-methods.js';
import { createNodeFormMethods } from '/static/js/vpn/node-form-methods.js';
import { createRemoteSearchConfig } from '/static/js/common/tom-select-helper.js';

const DEFAULT_NODE_FILTERS = Object.freeze({
    serverId: '',
    node_tag: '',
    type: '',
    coreType: '',
    protocol: '',
    disabled: '',
    deployed: '',
    sortBy: '',
    sortOrder: 'desc'
});

const nodeTable = new DataTable({
    data: {
        entityName: 'nodes',
        modalIdPrefix: 'node-',
        filters: { ...DEFAULT_NODE_FILTERS },
        servers: [],
        landingNodes: [],
        nodeGroupOptions: [],
        editNodeGroupOptions: [],
        nodeTypes: [],
        coreTypes: [],
        protocolTypes: [],
        availableTags: [],
        serverFilterSearch: null,
        createNodeGroupSelect: null,
        editNodeGroupSelect: null,
        nodeGroupSyncHighlight: {
            create: { protocol: false, port: false, inbound: false, outbound: false },
            edit: { protocol: false, port: false, inbound: false, outbound: false }
        },
        nodeGroupSyncMessage: {
            create: '',
            edit: ''
        },
        nodeGroupConfigLockState: {
            create: false,
            edit: false
        },
        nodeGroupSyncTimers: {
            create: null,
            edit: null
        },
        stats: {
            total: 0,
            active: 0,
            proxy: 0,
            landing: 0
        },
        newItem: {
            serverId: '',
            nodeGroup: '',
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
        portCheckTimer: null,
        editPortCheckTimer: null,
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
            this.initializeNodeGroupSelectHooks();
        },

        afterFetch() {
            const currentIds = new Set(this.records.map(record => record.id));
            this.selectedNodeIds = this.selectedNodeIds.filter(id => currentIds.has(id));
        },

        initializeSearchComponents() {
            setTimeout(() => {
                const selectElement = document.getElementById('server-filter-select');
                if (!selectElement) return;

                this.serverFilterSearch = new TomSelect(selectElement, createRemoteSearchConfig({
                    apiUrl: '/api/admin/servers',
                    valueField: 'id',
                    labelField: 'name',
                    searchField: ['name', 'ip'],
                    placeholder: '全部服务器',
                    plugins: ['remove_button'],
                    maxOptions: 50,
                    options: this.servers.slice(0, 10).map(server => ({
                        id: server.id,
                        name: this.formatServerDisplayLabel(server),
                        ip: server.ip
                    })),
                    dataTransform: (records) => records.map(server => ({
                        id: server.id,
                        name: this.formatServerDisplayLabel(server),
                        ip: server.ip
                    })),
                    onChange: (values) => {
                        this.filters.serverId = values.join(',');
                        this.onFilterChange();
                    }
                }));
            }, 100);
        },

        initializeNodeGroupSelectHooks() {
            const createModal = document.getElementById('node-createModal');
            if (createModal) {
                createModal.addEventListener('shown.bs.modal', () => {
                    this.initializeNodeGroupSelect(false);
                });
                createModal.addEventListener('hidden.bs.modal', () => this.destroyNodeGroupSelect(false));
            }

            const editModal = document.getElementById('node-editModal');
            if (editModal) {
                editModal.addEventListener('shown.bs.modal', () => {
                    this.initializeNodeGroupSelect(true);
                });
                editModal.addEventListener('hidden.bs.modal', () => this.destroyNodeGroupSelect(true));
            }
        },

        formatServerDisplayLabel(server) {
            if (!server) {
                return '';
            }

            if (server.data) {
                return this.formatServerDisplayLabel(server.data);
            }

            const ip = server.ip || '';
            let name = server.name || '';

            // 如果名称中包含括号，提取括号前的部分
            if (name && name.includes('(')) {
                name = name.substring(0, name.indexOf('(')).trim();
            }

            if (name && name !== ip) {
                return `${name} (${ip})`;
            }

            return ip;
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
            if (!this.serverFilterSearch) return;

            const serverIds = this.filters.serverId ? this.filters.serverId.split(',').filter(id => id) : [];
            this.serverFilterSearch.setValue(serverIds, true);
        },

        fetchServers() {
            fetch('/api/admin/nodes/servers')
                .then(response => response.json())
                .then(data => {
                    this.servers = data;

                    // 更新 Tom Select 默认选项
                    if (this.serverFilterSearch && this.servers.length > 0) {
                        const defaultOptions = this.servers.slice(0, 10).map(server => ({
                            id: server.id,
                            name: this.formatServerDisplayLabel(server),
                            ip: server.ip
                        }));
                        this.serverFilterSearch.clearOptions();
                        this.serverFilterSearch.addOptions(defaultOptions);
                    }

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

        fetchNodeGroupOptions(item, isEdit = false, keyword = '') {
            const optionKey = isEdit ? 'editNodeGroupOptions' : 'nodeGroupOptions';
            if (!item || item.type === '' || item.type === null || item.type === undefined || !item.coreType) {
                this[optionKey] = [];
                this.refreshNodeGroupSelect(isEdit);
                return;
            }

            const excludeId = isEdit ? (item.id || '') : '';
            const serverId = item.serverId || '';
            const query = keyword ? `&keyword=${encodeURIComponent(keyword)}` : '';
            return fetch(`/api/admin/nodes/group-options?type=${encodeURIComponent(item.type)}&coreType=${encodeURIComponent(item.coreType)}&excludeId=${encodeURIComponent(excludeId)}&serverId=${encodeURIComponent(serverId)}${query}`)
                .then(response => response.json())
                .then(data => {
                    this[optionKey] = Array.isArray(data) ? data : [];
                    if (!item?.nodeGroup || !this[optionKey].some(option => option.value === item.nodeGroup)) {
                        this.setNodeGroupConfigLocked(isEdit, false);
                    }
                    this.refreshNodeGroupSelect(isEdit);
                    return this[optionKey];
                })
                .catch(error => {
                    console.error('Error fetching node groups:', error);
                    this[optionKey] = [];
                    this.setNodeGroupConfigLocked(isEdit, false);
                    this.refreshNodeGroupSelect(isEdit);
                    return [];
                });
        },

        getSelectableNodeGroups(isEdit = false) {
            const optionKey = isEdit ? 'editNodeGroupOptions' : 'nodeGroupOptions';
            return this[optionKey] || [];
        },

        getNodeGroupSelectId(isEdit = false) {
            return isEdit ? 'node-group-select-edit' : 'node-group-select-create';
        },

        destroyNodeGroupSelect(isEdit = false) {
            const instanceKey = isEdit ? 'editNodeGroupSelect' : 'createNodeGroupSelect';
            if (this[instanceKey]) {
                this[instanceKey].destroy();
                this[instanceKey] = null;
            }
        },

        buildNodeGroupSelectOptions(isEdit = false) {
            const item = isEdit ? this.editedItem : this.newItem;
            const options = this.getSelectableNodeGroups(isEdit).slice(0, 10).map(group => ({
                value: group.value,
                label: group.label
            }));
            if (item?.nodeGroup && !options.some(option => option.value === item.nodeGroup)) {
                options.unshift({
                    value: item.nodeGroup,
                    label: item.nodeGroup
                });
            }
            return options.slice(0, 10);
        },

        fetchNodeGroupConfig(item, isEdit = false) {
            if (!item?.nodeGroup || item.type === '' || item.type === null || item.type === undefined || !item.coreType) {
                return Promise.resolve({ exists: false });
            }

            const excludeId = isEdit ? (item.id || '') : '';
            return fetch(`/api/admin/nodes/group-config?nodeGroup=${encodeURIComponent(item.nodeGroup)}&type=${encodeURIComponent(item.type)}&coreType=${encodeURIComponent(item.coreType)}&serverId=${encodeURIComponent(item.serverId || '')}&excludeId=${encodeURIComponent(excludeId)}`)
                .then(response => response.json())
                .catch(error => {
                    console.error('Error fetching node group config:', error);
                    return { exists: false };
                });
        },

        applyNodeGroupConfig(item, config, isEdit = false) {
            if (!item || !config?.exists || !config?.compatible) {
                return;
            }

            const changedFields = [];
            if (item.protocol !== config.protocol) {
                item.protocol = config.protocol || '';
                changedFields.push('protocol');
            }
            if (item.port !== config.port) {
                item.port = config.port ?? null;
                changedFields.push('port');
            }

            const normalizedOutId = config.outId ?? (item.type === 0 ? 0 : null);
            if (item.outId !== normalizedOutId) {
                item.outId = normalizedOutId;
                changedFields.push('outbound');
            }

            const inboundConfig = config.inbound || {};
            const inboundJson = JSON.stringify(inboundConfig, null, 2);
            if (isEdit) {
                if (this.editedNodeInboundJson !== inboundJson) {
                    this.editedNodeInbound = inboundConfig;
                    this.editedNodeInboundJson = inboundJson;
                    changedFields.push('inbound');
                }
                this.checkEditPortAvailability();
            } else {
                if (this.newNodeInboundJson !== inboundJson) {
                    this.newNodeInbound = inboundConfig;
                    this.newNodeInboundJson = inboundJson;
                    changedFields.push('inbound');
                }
                this.checkPortAvailability();
            }

            if (changedFields.length > 0) {
                this.flashNodeGroupSyncFeedback(isEdit, changedFields, config.referenceNodeName);
            }
            this.setNodeGroupConfigLocked(isEdit, !!config.lockConfig);
        },

        handleNodeGroupChange(value, isEdit = false) {
            const item = isEdit ? this.editedItem : this.newItem;
            if (!item) {
                return;
            }

            item.nodeGroup = value || '';
            this.clearNodeGroupSyncFeedback(isEdit);
            this.setNodeGroupConfigLocked(isEdit, false);
            if (!item.nodeGroup) {
                return;
            }

            const optionList = this.getSelectableNodeGroups(isEdit);
            const isExistingGroup = optionList.some(option => option.value === item.nodeGroup);
            if (!isExistingGroup) {
                return;
            }

            this.fetchNodeGroupConfig(item, isEdit).then(config => {
                if (config?.exists && config?.compatible) {
                    this.applyNodeGroupConfig(item, config, isEdit);
                    return;
                }
                this.setNodeGroupConfigLocked(isEdit, false);
                if (config?.message) {
                    ToastUtils.show('Warning', config.message, 'warning');
                }
            });
        },

        flashNodeGroupSyncFeedback(isEdit = false, changedFields = [], referenceNodeName = '') {
            const mode = isEdit ? 'edit' : 'create';
            const nextState = { protocol: false, port: false, inbound: false, outbound: false };
            changedFields.forEach(field => {
                if (Object.prototype.hasOwnProperty.call(nextState, field)) {
                    nextState[field] = true;
                }
            });
            this.nodeGroupSyncHighlight = {
                ...this.nodeGroupSyncHighlight,
                [mode]: nextState
            };
            this.nodeGroupSyncMessage = {
                ...this.nodeGroupSyncMessage,
                [mode]: referenceNodeName
                    ? `已按节点组配置同步，来源节点：${referenceNodeName}`
                    : '已按节点组配置同步'
            };

            if (this.nodeGroupSyncTimers[mode]) {
                clearTimeout(this.nodeGroupSyncTimers[mode]);
            }
            this.nodeGroupSyncTimers[mode] = setTimeout(() => this.clearNodeGroupSyncFeedback(isEdit, true), 2200);
        },

        clearNodeGroupSyncFeedback(isEdit = false, preserveMessage = false) {
            const mode = isEdit ? 'edit' : 'create';
            this.nodeGroupSyncHighlight = {
                ...this.nodeGroupSyncHighlight,
                [mode]: { protocol: false, port: false, inbound: false, outbound: false }
            };
            if (!preserveMessage) {
                this.nodeGroupSyncMessage = {
                    ...this.nodeGroupSyncMessage,
                    [mode]: ''
                };
            }
            if (this.nodeGroupSyncTimers[mode]) {
                clearTimeout(this.nodeGroupSyncTimers[mode]);
                this.nodeGroupSyncTimers[mode] = null;
            }
        },

        isNodeGroupFieldHighlighted(isEdit = false, field) {
            const mode = isEdit ? 'edit' : 'create';
            return !!this.nodeGroupSyncHighlight?.[mode]?.[field];
        },

        setNodeGroupConfigLocked(isEdit = false, locked = false) {
            const mode = isEdit ? 'edit' : 'create';
            this.nodeGroupConfigLockState = {
                ...this.nodeGroupConfigLockState,
                [mode]: !!locked
            };
        },

        isNodeGroupConfigLocked(isEdit = false) {
            const mode = isEdit ? 'edit' : 'create';
            return !!this.nodeGroupConfigLockState?.[mode];
        },

        getNodeGroupFieldClass(isEdit = false, field) {
            return {
                'bg-blue-lt border border-blue rounded-3 p-2': this.isNodeGroupFieldHighlighted(isEdit, field)
            };
        },

        initializeNodeGroupSelect(isEdit = false) {
            const item = isEdit ? this.editedItem : this.newItem;
            if (!item) {
                return;
            }

            this.$nextTick(() => {
                const selectElement = document.getElementById(this.getNodeGroupSelectId(isEdit));
                if (!selectElement) {
                    return;
                }

                this.destroyNodeGroupSelect(isEdit);
                const instanceKey = isEdit ? 'editNodeGroupSelect' : 'createNodeGroupSelect';
                this[instanceKey] = new TomSelect(selectElement, {
                    valueField: 'value',
                    labelField: 'label',
                    searchField: ['label', 'value'],
                    placeholder: '请输入或选择节点组',
                    options: this.buildNodeGroupSelectOptions(isEdit),
                    items: item.nodeGroup ? [item.nodeGroup] : [],
                    maxItems: 1,
                    maxOptions: 10,
                    create: (input) => {
                        const value = (input || '').trim();
                        return value ? { value, label: value } : false;
                    },
                    openOnFocus: true,
                    preload: false,
                    load: (query, callback) => {
                        this.fetchNodeGroupOptions(item, isEdit, query).then(options => callback(options)).catch(() => callback());
                    },
                    onChange: (value) => {
                        this.handleNodeGroupChange(value, isEdit);
                    }
                });

                this[instanceKey].refreshOptions(false);
            });
        },

        refreshNodeGroupSelect(isEdit = false) {
            const instanceKey = isEdit ? 'editNodeGroupSelect' : 'createNodeGroupSelect';
            const item = isEdit ? this.editedItem : this.newItem;
            if (!item || !this[instanceKey]) {
                return;
            }

            const options = this.buildNodeGroupSelectOptions(isEdit);
            this[instanceKey].clearOptions();
            this[instanceKey].addOptions(options);
            this[instanceKey].setValue(item.nodeGroup || '', true);
            this[instanceKey].refreshOptions(false);
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

            const serverIds = this.filters.serverId.split(',').filter(id => id);
            if (serverIds.length === 0) {
                return '';
            }

            const serverLabels = serverIds.map(id => {
                const server = this.servers.find(item => String(item.id) === String(id));
                return server ? this.formatServerDisplayLabel(server) : id;
            });

            return serverLabels.join(', ');
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
                    key: 'node_tag',
                    label: '标签',
                    isActive: value => value !== '',
                    getValueLabel: value => this.availableTags.find(tag => String(tag.id) === String(value))?.name || value
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

        viewNodeConfig(node) {
            this.editedNodeInboundJson = JSON.stringify(node.inbound || {}, null, 2);
            this.editedNodeRuleJson = JSON.stringify(node.rule || {}, null, 2);
            this.editedNodeOutbound = node.outName + ' (' + node.serverHost + ':' + node.outPort + ')';
            this.viewConfigModal = new Modal(document.getElementById('viewConfigModal'));
            this.viewConfigModal.show();
        },

        getApiUrl() {
            return '/api/admin/nodes';
        },

        toggleSort(field) {
            if (this.filters.sortBy === field) {
                if (this.filters.sortOrder === 'desc') {
                    this.filters.sortOrder = 'asc';
                } else if (this.filters.sortOrder === 'asc') {
                    this.filters.sortBy = '';
                    this.filters.sortOrder = 'desc';
                } else {
                    this.filters.sortOrder = 'desc';
                }
            } else {
                this.filters.sortBy = field;
                this.filters.sortOrder = 'desc';
            }
            this.currentPage = 1;
            this.fetchRecords();
        },

        getSortIcon(field) {
            if (this.filters.sortBy !== field) return 'ti ti-selector';
            return this.filters.sortOrder === 'asc' ? 'ti ti-sort-ascending' : 'ti ti-sort-descending';
        },

        isSortActive(field) {
            return this.filters.sortBy === field;
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

        ...createNodeFormMethods(),
        ...createNodeDeployMethods(),

        ...createResponsiveFilterMethods({
            createDefaultFilters: () => ({ ...DEFAULT_NODE_FILTERS }),
            applyFilters() {
                this.onFilterChange();
            },
            onReset() {
                if (this.serverFilterSearch) {
                    this.serverFilterSearch.clear();
                }
            },
            onClear(key) {
                if (key === 'serverId' && this.serverFilterSearch) {
                    this.serverFilterSearch.clear();
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
