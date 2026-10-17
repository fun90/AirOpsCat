import { DataTable } from '/static/js/common/data-table.js';
import { Dropdown, Modal } from '/static/tabler/js/tabler.esm.min.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';
import { createNodeDeployMethods } from '/static/js/vpn/node-deploy-methods.js';
import { createNodeFormMethods } from '/static/js/vpn/node-form-methods.js';
import { createRemoteSearchConfig, withTomSelectSearchClear } from '/static/js/common/tom-select-helper.js';

const DEFAULT_NODE_FILTERS = Object.freeze({
    serverId: '',
    node_tag: '',
    type: '',
    coreType: '',
    protocol: '',
    disabled: '',
    deploymentStatus: '',
    sortBy: '',
    sortOrder: 'desc'
});

const NODE_DEPLOYMENT_STATUS = Object.freeze({
    PENDING_DEPLOY: 0,
    DEPLOYED: 1,
    PENDING_DELETE: 2
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
        serverFilterSearches: [],
        createDnsRecordSelect: null,
        editDnsRecordSelect: null,
        stats: {
            total: 0,
            active: 0,
            proxy: 0,
            landing: 0
        },
        newItem: {
            placements: [],
            dnsRecordId: '',
            port: null,
            coreType: 'sing-box',
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
        batchTagModal: null,
        batchActionDropdown: null,
        batchActionDropdownCloseTimer: null,
        deploymentHistoryModal: null,
        deploymentVersionDetailModal: null,
        deploymentHistoryNode: null,
        deploymentVersions: [],
        deploymentVersionDetail: null,
        loadingDeploymentVersions: false,
        loadingDeploymentVersionDetail: false,
        restoringDeploymentVersion: false,
        selectedNodeIds: [],
        onlineConnectionsModal: null,
        onlineConnectionsNode: null,
        onlineConnections: [],
        onlineConnectionsLoading: false,
        batchTagForm: {
            mode: 'REPLACE',
            tagIds: []
        },
        batchDeploying: false,
        batchTagUpdating: false,
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
            this.initializeDnsRecordSelectHooks();
        },

        afterFetch() {
            const currentIds = new Set(this.records.map(record => record.id));
            this.selectedNodeIds = this.selectedNodeIds.filter(id => currentIds.has(id));
        },

        openBatchActionsDropdown() {
            const toggle = document.getElementById('node-batch-actions-toggle');
            if (!toggle) {
                return;
            }

            this.cancelCloseBatchActionsDropdown();
            if (!this.batchActionDropdown) {
                this.batchActionDropdown = new Dropdown(toggle);
            }
            this.batchActionDropdown.show();
        },

        scheduleCloseBatchActionsDropdown() {
            this.cancelCloseBatchActionsDropdown();
            this.batchActionDropdownCloseTimer = setTimeout(() => {
                if (this.batchActionDropdown) {
                    this.batchActionDropdown.hide();
                }
                this.batchActionDropdownCloseTimer = null;
            }, 120);
        },

        cancelCloseBatchActionsDropdown() {
            if (this.batchActionDropdownCloseTimer) {
                clearTimeout(this.batchActionDropdownCloseTimer);
                this.batchActionDropdownCloseTimer = null;
            }
        },

        initializeSearchComponents() {
            setTimeout(() => {
                const selectElements = ['nodeServerFilter', 'nodeMobileServerFilter']
                    .map(selectId => document.getElementById(selectId))
                    .filter(Boolean);
                if (!selectElements.length) return;

                this.serverFilterSearches.forEach(instance => instance.destroy());
                this.serverFilterSearches = selectElements.map(selectElement => new TomSelect(selectElement, createRemoteSearchConfig({
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
                        this.syncServerFilterSearches();
                        this.onFilterChange();
                    }
                })));
                this.syncServerFilterSearches();
            }, 100);
        },

        initializeDnsRecordSelectHooks() {
            [['node-createModal', false], ['node-editModal', true]].forEach(([modalId, isEdit]) => {
                const modal = document.getElementById(modalId);
                if (modal) {
                    modal.addEventListener('shown.bs.modal', () => this.initializeDnsRecordSelect(isEdit));
                    modal.addEventListener('hidden.bs.modal', () => this.destroyDnsRecordSelect(isEdit));
                }
            });
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

        syncAccessHostSelection(placement) {
            if (!placement) {
                return;
            }
            if (!placement.serverId) {
                placement.accessHostId = '';
                return;
            }

            const availableHosts = this.getServerHosts(placement.serverId);
            if (!availableHosts.some(host => String(host.id || '') === String(placement.accessHostId))) {
                placement.accessHostId = '';
            }
        },

        syncServerFilterSearches(serverItem = null) {
            if (!this.serverFilterSearches.length) return;

            const serverIds = this.filters.serverId ? this.filters.serverId.split(',').filter(id => id) : [];
            this.serverFilterSearches.forEach(instance => {
                instance.setValue(serverIds, true);
            });
        },

        fetchServers() {
            fetch('/api/admin/nodes/servers')
                .then(response => response.json())
                .then(data => {
                    this.servers = data;

                    // 更新 Tom Select 默认选项
                    if (this.serverFilterSearches.length && this.servers.length > 0) {
                        const defaultOptions = this.servers.slice(0, 10).map(server => ({
                            id: server.id,
                            name: this.formatServerDisplayLabel(server),
                            ip: server.ip
                        }));
                        this.serverFilterSearches.forEach(instance => {
                            instance.clearOptions();
                            instance.addOptions(defaultOptions);
                        });
                    }

                    this.syncServerFilterSearches();
                    if (this.servers.length > 0 && !this.newItem.placements.length) {
                        this.newItem.placements.push(this.createPlacement(this.servers[0].id));
                    }
                    [this.newItem, this.editedItem].forEach(item => {
                        (item?.placements || []).forEach(placement => this.syncAccessHostSelection(placement));
                    });
                })
                .catch(error => {
                    console.error('Error fetching servers:', error);
                });
        },

        getDnsRecordSelectId(isEdit = false) {
            return isEdit ? 'node-dns-record-select-edit' : 'node-dns-record-select-create';
        },

        destroyDnsRecordSelect(isEdit = false) {
            const instanceKey = isEdit ? 'editDnsRecordSelect' : 'createDnsRecordSelect';
            if (this[instanceKey]) {
                this[instanceKey].destroy();
                this[instanceKey] = null;
            }
        },

        initializeDnsRecordSelect(isEdit = false) {
            const selectElement = document.getElementById(this.getDnsRecordSelectId(isEdit));
            if (!selectElement || typeof TomSelect === 'undefined') {
                return;
            }
            this.destroyDnsRecordSelect(isEdit);
            const item = isEdit ? this.editedItem : this.newItem;
            const instanceKey = isEdit ? 'editDnsRecordSelect' : 'createDnsRecordSelect';
            this[instanceKey] = new TomSelect(selectElement, createRemoteSearchConfig({
                apiUrl: '/api/admin/domain-dns-records',
                valueField: 'id',
                labelField: 'fullName',
                searchField: ['fullName'],
                placeholder: '不绑定（每个暴露的部署位输出一条）',
                minQueryLength: 1,
                render: {
                    option: (data, escape) => `<div>
                        <div>${escape(data.fullName || '')}</div>
                        <div class="text-muted small">${escape(data.type || '')}${data.domain ? ` · ${escape(data.domain)}` : ''}</div>
                    </div>`,
                    item: (data, escape) => `<div>${escape(data.fullName || '')}</div>`
                },
                onChange: (value) => {
                    item.dnsRecordId = value || '';
                }
            }));
            if (item.dnsRecordId) {
                this[instanceKey].addOption({ id: item.dnsRecordId, fullName: item.dnsRecordName || String(item.dnsRecordId) });
                this[instanceKey].setValue(item.dnsRecordId, true);
            }
        },

        getPlacementBadgeClass(placement) {
            if (placement.enabled === 0) {
                return 'bg-secondary-lt';
            }
            if (placement.deployed === NODE_DEPLOYMENT_STATUS.PENDING_DELETE) {
                return 'bg-danger-lt';
            }
            if (placement.deployed !== NODE_DEPLOYMENT_STATUS.DEPLOYED) {
                return 'bg-warning-lt';
            }
            return placement.exposed === 0 ? 'bg-azure-lt' : 'bg-success-lt';
        },

        getPlacementTitle(placement) {
            const parts = [
                placement.serverHost || placement.serverIp || '',
                placement.enabled === 0 ? '已停用' : this.getDeploymentStatusLabel(placement.deployed),
                placement.exposed === 0 ? '不暴露' : '暴露',
                `优先级 ${placement.priority ?? 0}`
            ];
            return parts.filter(Boolean).join(' · ');
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
                    key: 'deploymentStatus',
                    label: '部署',
                    isActive: value => value !== '',
                    getValueLabel: value => this.getDeploymentStatusLabel(Number(value))
                }
            ];
        },

        isPendingDeploy(node) {
            return node && node.deployed === NODE_DEPLOYMENT_STATUS.PENDING_DEPLOY;
        },

        isDeployed(node) {
            return node && node.deployed === NODE_DEPLOYMENT_STATUS.DEPLOYED;
        },

        isPendingDelete(node) {
            return node && node.deployed === NODE_DEPLOYMENT_STATUS.PENDING_DELETE;
        },

        getDeploymentStatusLabel(deployed) {
            switch (deployed) {
                case NODE_DEPLOYMENT_STATUS.DEPLOYED:
                    return '已部署';
                case NODE_DEPLOYMENT_STATUS.PENDING_DELETE:
                    return '待删除';
                case NODE_DEPLOYMENT_STATUS.PENDING_DEPLOY:
                default:
                    return '待部署';
            }
        },

        canDeleteNode(node) {
            return this.isPendingDeploy(node) || this.isDeployed(node);
        },

        getDeleteModalDescription() {
            if (this.selectedItem && this.isDeployed(this.selectedItem)) {
                return '该节点已部署，确认后会先标记为待删除，部署刷新成功后再从系统中移除。';
            }
            return '该节点尚未部署到远端，确认后将直接删除，数据无法恢复。';
        },

        getFilterProtocolOptions() {
            return this.protocolTypes.filter(protocol => {
                return this.filters.type === '' || String(protocol.type) === String(this.filters.type);
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

        getStatsUrl() {
            return '/api/admin/nodes/stats';
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

        deleteItem() {
            if (!this.selectedItem) return;

            fetch(`/api/admin/nodes/${this.selectedItem.id}`, {
                method: 'DELETE'
            })
                .then(response => {
                    if (!response.ok) {
                        return response.json().then(data => {
                            throw new Error(data.message || '删除失败');
                        });
                    }
                    return response.json().catch(() => ({ message: '删除成功' }));
                })
                .then(data => {
                    this.fetchRecords();
                    this.refreshStats();
                    ToastUtils.show('Success', data.message || '删除成功', 'success');
                    this.deleteModal.hide();
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '删除失败', 'danger');
                });
        },

        switchActivePlacement(placement) {
            if (!this.editedItem?.id || !placement?.id) return;

            fetch(`/api/admin/nodes/${this.editedItem.id}/failover/switch`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nodeServerId: placement.id })
            })
                .then(async response => {
                    const data = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        throw new Error(data.message || '切换失败');
                    }
                    return data;
                })
                .then(data => {
                    this.editedItem.activeNodeServerId = placement.id;
                    this.fetchRecords();
                    ToastUtils.show('Success', data.message || '已切换', 'success');
                })
                .catch(error => {
                    console.error('Error:', error);
                    ToastUtils.show('Error', error.message || '切换失败', 'danger');
                });
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

        viewOnlineConnections(node) {
            this.onlineConnectionsNode = node;
            this.onlineConnections = [];
            this.onlineConnectionsLoading = true;
            if (!this.onlineConnectionsModal) {
                this.onlineConnectionsModal = new Modal(document.getElementById('nodeOnlineConnectionsModal'));
            }
            this.onlineConnectionsModal.show();
            this.fetchNodeOnlineConnections(node);
        },

        fetchNodeOnlineConnections(node) {
            if (!node || !node.id) {
                this.onlineConnectionsLoading = false;
                return;
            }

            fetch(`/api/admin/nodes/${node.id}/online-accounts`)
                .then(response => {
                    if (!response.ok) {
                        throw new Error('获取在线连接失败');
                    }
                    return response.json();
                })
                .then(data => {
                    this.onlineConnections = Array.isArray(data) ? data : [];
                })
                .catch(error => {
                    console.error('Error fetching node online connections:', error);
                    ToastUtils.show('Error', '获取在线连接失败', 'danger');
                })
                .finally(() => {
                    this.onlineConnectionsLoading = false;
                });
        },

        refreshNodeOnlineConnections() {
            if (this.onlineConnectionsNode) {
                this.fetchNodeOnlineConnections(this.onlineConnectionsNode);
            }
        },

        getOnlineDuration(sessionStartTime) {
            if (!sessionStartTime) {
                return '-';
            }
            const now = new Date();
            const start = new Date(sessionStartTime);
            const minutes = Math.floor(Math.max(0, now.getTime() - start.getTime()) / 60000);
            if (minutes < 60) {
                return `${minutes} 分钟`;
            }
            const hours = Math.floor(minutes / 60);
            const remainingMinutes = minutes % 60;
            if (hours < 24) {
                return `${hours}小时${remainingMinutes}分钟`;
            }
            const days = Math.floor(hours / 24);
            return `${days}天${hours % 24}小时`;
        },

        ...createNodeFormMethods(),
        ...createNodeDeployMethods(),

        ...createResponsiveFilterMethods({
            createDefaultFilters: () => ({ ...DEFAULT_NODE_FILTERS }),
            applyFilters() {
                this.onFilterChange();
            },
            onReset() {
                this.serverFilterSearches.forEach(instance => instance.clear());
            },
            onClear(key) {
                if (key === 'serverId') {
                    this.serverFilterSearches.forEach(instance => instance.clear());
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
