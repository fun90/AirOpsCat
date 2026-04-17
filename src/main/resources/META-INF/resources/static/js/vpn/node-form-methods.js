export function createNodeFormMethods() {
    return {
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

        onTypeChange() {
            if (this.newItem.type === 1) {
                this.newItem.outId = null;
            }
            this.syncProtocolSelection(this.newItem, false);
            this.fetchNodeGroupOptions(this.newItem);
        },

        onCoreTypeChange() {
            this.syncProtocolSelection(this.newItem, false);
            this.fetchNodeGroupOptions(this.newItem);
        },

        onEditTypeChange() {
            if (this.editedItem.type === 1) {
                this.editedItem.outId = 0;
            }
            this.syncProtocolSelection(this.editedItem, true);
            this.fetchNodeGroupOptions(this.editedItem, true);
        },

        onEditCoreTypeChange() {
            this.syncProtocolSelection(this.editedItem, true);
            this.fetchNodeGroupOptions(this.editedItem, true);
        },

        onServerChange() {
            this.syncAccessHostSelection(this.newItem);
            this.schedulePortAvailabilityCheck();
            this.fetchNodeGroupOptions(this.newItem);
        },

        onEditServerChange() {
            this.syncAccessHostSelection(this.editedItem);
            this.scheduleEditPortAvailabilityCheck();
            this.fetchNodeGroupOptions(this.editedItem, true);
        },

        schedulePortAvailabilityCheck() {
            if (this.portCheckTimer) {
                clearTimeout(this.portCheckTimer);
            }

            if (!this.newItem.serverId || !this.newItem.port) {
                this.portCheckMessage = '';
                return;
            }

            this.portCheckTimer = setTimeout(() => {
                this.checkPortAvailability();
                this.portCheckTimer = null;
            }, 300);
        },

        scheduleEditPortAvailabilityCheck() {
            if (this.editPortCheckTimer) {
                clearTimeout(this.editPortCheckTimer);
            }

            if (!this.editedItem?.serverId || !this.editedItem?.port || !this.editedItem?.id) {
                this.editPortCheckMessage = '';
                return;
            }

            this.editPortCheckTimer = setTimeout(() => {
                this.checkEditPortAvailability();
                this.editPortCheckTimer = null;
            }, 300);
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
            return item && item.coreType ? item.coreType : 'sing-box';
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
                    nodeGroup: this.newItem.nodeGroup || null,
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
                    nodeGroup: this.editedItem.nodeGroup || null,
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
            this.clearNodeGroupSyncFeedback(false);
            this.setNodeGroupConfigLocked(false, false);
            this.newItem = {
                serverId: this.servers.length > 0 ? this.servers[0].id : '',
                nodeGroup: '',
                accessHostId: '',
                port: null,
                coreType: 'sing-box',
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
            this.nodeGroupOptions = [];
            this.refreshNodeGroupSelect(false);
            this.onProtocolChange();
            this.fetchNodeGroupOptions(this.newItem);

            this.newNodeRuleJson = '{}';
            this.portCheckMessage = '';
            if (this.portCheckTimer) {
                clearTimeout(this.portCheckTimer);
                this.portCheckTimer = null;
            }
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
            this.clearNodeGroupSyncFeedback(true);
            this.setNodeGroupConfigLocked(true, false);
            this.editedNodeInbound = node.inbound || { protocol: 'vless' };
            this.editedNodeInboundJson = JSON.stringify(node.inbound || {}, null, 2);
            this.editedNodeRuleJson = JSON.stringify(node.rule || {}, null, 2);

            this.editPortCheckMessage = '';
            if (this.editPortCheckTimer) {
                clearTimeout(this.editPortCheckTimer);
                this.editPortCheckTimer = null;
            }
            this.loadNodeTags(node.id);
            this.editNodeGroupOptions = [];
            this.fetchNodeGroupOptions(node, true);
            this.fetchNodeGroupConfig(node, true)
                .then(config => this.setNodeGroupConfigLocked(true, !!config?.lockConfig))
                .catch(() => this.setNodeGroupConfigLocked(true, false));

            return {
                id: node.id,
                serverId: node.serverId,
                nodeGroup: node.nodeGroup || '',
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
        }
    };
}
