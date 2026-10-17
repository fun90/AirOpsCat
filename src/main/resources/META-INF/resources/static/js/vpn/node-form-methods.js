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

        createPlacement(serverId = '', priority = 0) {
            return {
                id: null,
                serverId,
                accessHostId: '',
                exposed: true,
                priority,
                enabled: true,
                name: '',
                tagIds: [],
                deployed: null
            };
        },

        addPlacement(item, isEdit = false) {
            const usedServerIds = new Set(item.placements.map(placement => String(placement.serverId)));
            const server = this.servers.find(candidate => !usedServerIds.has(String(candidate.id)));
            const maxPriority = item.placements.reduce((max, placement) => Math.max(max, Number(placement.priority) || 0), -10);
            const placement = this.createPlacement(server ? server.id : '', maxPriority + 10);
            placement.tagIds = [...(this.getPrimaryPlacement(item)?.tagIds || [])];
            item.placements.push(placement);
            this.onPlacementServerChange(item, placement, isEdit);
        },

        removePlacement(item, index, isEdit = false) {
            item.placements.splice(index, 1);
            this.schedulePlacementPortCheck(isEdit);
        },

        // 主部署位：启用且 priority 最小，用于生成默认入站配置
        getPrimaryPlacement(item) {
            const placements = (item?.placements || []).filter(placement => placement.serverId);
            const enabled = placements.filter(placement => placement.enabled);
            const candidates = enabled.length ? enabled : placements;
            return candidates.slice().sort((a, b) => (Number(a.priority) || 0) - (Number(b.priority) || 0))[0] || null;
        },

        getServerLabel(serverId) {
            const server = this.servers.find(item => String(item.id) === String(serverId));
            return server ? this.formatServerDisplayLabel(server) : String(serverId || '');
        },

        onPlacementServerChange(item, placement, isEdit = false) {
            this.syncAccessHostSelection(placement);
            this.schedulePlacementPortCheck(isEdit);
        },

        onPlacementAccessHostChange(item, placement, isEdit = false) {
            if (placement === this.getPrimaryPlacement(item) && ['vless', 'hysteria2'].includes(item.protocol)) {
                isEdit ? this.onEditProtocolChange() : this.onProtocolChange();
            }
        },

        schedulePortAvailabilityCheck() {
            this.schedulePlacementPortCheck(false);
        },

        scheduleEditPortAvailabilityCheck() {
            this.schedulePlacementPortCheck(true);
        },

        schedulePlacementPortCheck(isEdit = false) {
            const timerKey = isEdit ? 'editPortCheckTimer' : 'portCheckTimer';
            const messageKey = isEdit ? 'editPortCheckMessage' : 'portCheckMessage';
            const item = isEdit ? this.editedItem : this.newItem;
            if (this[timerKey]) {
                clearTimeout(this[timerKey]);
                this[timerKey] = null;
            }

            const serverIds = this.getEnabledPlacementServerIds(item);
            if (!serverIds.length || !item?.port) {
                this[messageKey] = '';
                return;
            }

            this[timerKey] = setTimeout(() => {
                this.checkPlacementPorts(item, serverIds, messageKey);
                this[timerKey] = null;
            }, 300);
        },

        getEnabledPlacementServerIds(item) {
            return [...new Set((item?.placements || [])
                .filter(placement => placement.enabled && placement.serverId)
                .map(placement => placement.serverId))];
        },

        checkPlacementPorts(item, serverIds, messageKey) {
            const nodeQuery = item.id ? `&nodeId=${encodeURIComponent(item.id)}` : '';
            Promise.all(serverIds.map(serverId =>
                fetch(`/api/admin/nodes/check-port?serverId=${encodeURIComponent(serverId)}&port=${encodeURIComponent(item.port)}${nodeQuery}`)
                    .then(response => response.json())
                    .then(data => ({ serverId, available: !!data.available }))
            ))
                .then(results => {
                    const conflicts = results.filter(result => !result.available).map(result => this.getServerLabel(result.serverId));
                    this[messageKey] = conflicts.length ? `端口已被占用：${conflicts.join('、')}` : '端口可用';
                })
                .catch(error => {
                    console.error('Error checking port:', error);
                    this[messageKey] = '检查端口失败';
                });
        },

        getAvailablePort() {
            this.fillAvailablePort(this.newItem, false);
        },

        getEditAvailablePort() {
            this.fillAvailablePort(this.editedItem, true);
        },

        fillAvailablePort(item, isEdit = false) {
            const serverIds = this.getEnabledPlacementServerIds(item);
            if (!serverIds.length) {
                ToastUtils.show('Warning', '请先添加部署服务器', 'warning');
                return;
            }

            fetch(`/api/admin/nodes/available-port?serverId=${encodeURIComponent(serverIds.join(','))}`)
                .then(response => response.json())
                .then(data => {
                    item.port = data.port;
                    this.schedulePlacementPortCheck(isEdit);
                })
                .catch(error => {
                    console.error('Error getting available port:', error);
                    ToastUtils.show('Error', '获取可用端口失败', 'danger');
                });
        },

        buildDefaultInboundUrl(item) {
            const primary = this.getPrimaryPlacement(item);
            const coreType = this.getDefaultInboundCoreType(item);
            return `/api/admin/nodes/default-inbound?protocol=${encodeURIComponent(item.protocol || '')}`
                + `&serverId=${encodeURIComponent(primary?.serverId || '')}`
                + `&accessHostId=${encodeURIComponent(primary?.accessHostId || '')}`
                + `&coreType=${encodeURIComponent(coreType)}`;
        },

        onProtocolChange() {
            fetch(this.buildDefaultInboundUrl(this.newItem))
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
            fetch(this.buildDefaultInboundUrl(this.editedItem))
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
            return this.protocolTypes.filter(protocol => {
                return protocol.type === item.type;
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

        validatePlacements(item) {
            const placements = item.placements || [];
            if (!placements.length) {
                return '请添加部署服务器';
            }
            if (placements.some(placement => !placement.serverId)) {
                return '请为每个部署位选择服务器';
            }
            if (new Set(placements.map(placement => String(placement.serverId))).size !== placements.length) {
                return '同一节点不能重复选择同一服务器';
            }
            if (!placements.some(placement => placement.enabled)) {
                return '至少需要一个启用的部署服务器';
            }
            if (item.dnsRecordId) {
                const tagSets = placements.filter(placement => placement.enabled)
                    .map(placement => [...(placement.tagIds || [])].map(Number).sort((a, b) => a - b).join(','));
                if (new Set(tagSets).size > 1) {
                    return '共享 DNS 的启用部署位必须使用相同标签';
                }
            }
            return '';
        },

        validateNodeForm(item) {
            let isValid = true;
            this.validationErrors = {};

            const placementError = this.validatePlacements(item);
            if (placementError) {
                this.validationErrors.placements = placementError;
                isValid = false;
            }

            if (!item.port) {
                this.validationErrors.port = '请输入端口';
                isValid = false;
            } else if (item.port < 1 || item.port > 65535) {
                this.validationErrors.port = '端口范围应为 1-65535';
                isValid = false;
            }

            if (!item.name) {
                this.validationErrors.name = '请填写节点名称';
                isValid = false;
            }

            if (item.type === null || item.type === undefined) {
                this.validationErrors.type = '请选择节点类型';
                isValid = false;
            }

            if (!item.protocol || !this.getAvailableProtocols(item).some(protocol => protocol.value === item.protocol)) {
                this.validationErrors.protocol = '请选择可用协议';
                isValid = false;
            }

            return isValid;
        },

        validateCreateForm() {
            return this.validateNodeForm(this.newItem);
        },

        validateEditForm() {
            return this.validateNodeForm(this.editedItem);
        },

        preparePlacementData(item) {
            return (item.placements || []).map(placement => ({
                serverId: placement.serverId,
                accessHostId: placement.accessHostId || null,
                exposed: placement.exposed ? 1 : 0,
                priority: Number(placement.priority) || 0,
                enabled: placement.enabled ? 1 : 0,
                name: placement.name || null,
                tagIds: placement.tagIds || []
            }));
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
                    placements: this.preparePlacementData(this.newItem),
                    dnsRecordId: this.newItem.dnsRecordId || null,
                    port: this.newItem.port,
                    coreType: 'sing-box',
                    protocol: this.newItem.protocol,
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
                    placements: this.preparePlacementData(this.editedItem),
                    dnsRecordId: this.editedItem.dnsRecordId || null,
                    port: this.editedItem.port,
                    coreType: 'sing-box',
                    protocol: this.editedItem.protocol,
                    type: this.editedItem.type,
                    level: this.editedItem.level || 0,
                    disabled: this.editedItem.disabled,
                    name: this.editedItem.name || null,
                    remark: this.editedItem.remark || null,
                    inbound: this.editedNodeInbound,
                    outId: this.editedItem.outId === 0 ? null : this.editedItem.outId,
                    rule: ruleConfig
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
                placements: [this.createPlacement(this.servers.length > 0 ? this.servers[0].id : '')],
                dnsRecordId: '',
                port: null,
                coreType: 'sing-box',
                protocol: 'vless',
                type: 0,
                level: 0,
                disabled: false,
                name: null,
                remark: '',
                inbound: null,
                outId: null,
                rule: null
            };

            this.newNodeInbound = {
                protocol: 'vless'
            };
            this.onProtocolChange();

            this.newNodeRuleJson = '{}';
            this.portCheckMessage = '';
            if (this.portCheckTimer) {
                clearTimeout(this.portCheckTimer);
                this.portCheckTimer = null;
            }
        },

        prepareEditForm(node) {
            this.fetchLandingNodes();
            this.editedNodeInbound = node.inbound || { protocol: 'vless' };
            this.editedNodeInboundJson = JSON.stringify(node.inbound || {}, null, 2);
            this.editedNodeRuleJson = JSON.stringify(node.rule || {}, null, 2);

            this.editPortCheckMessage = '';
            if (this.editPortCheckTimer) {
                clearTimeout(this.editPortCheckTimer);
                this.editPortCheckTimer = null;
            }
            // 待删除的部署位正在移除，不在表单中展示；再次添加同一服务器即可恢复
            const placements = (node.placements || [])
                .filter(placement => placement.deployed !== 2)
                .map(placement => ({
                    id: placement.id,
                    serverId: placement.serverId,
                    accessHostId: placement.accessHostId || '',
                    exposed: placement.exposed !== 0,
                    priority: placement.priority ?? 0,
                    enabled: placement.enabled !== 0,
                    name: placement.name || '',
                    tagIds: [...(placement.tagIds || [])],
                    deployed: placement.deployed
                }));

            return {
                id: node.id,
                placements,
                dnsRecordId: node.dnsRecordId || '',
                dnsRecordName: node.dnsRecordName || '',
                port: node.port,
                coreType: node.coreType,
                protocol: node.protocol,
                type: node.type,
                level: node.level || 0,
                disabled: node.disabled,
                name: node.name || null,
                remark: node.remark || '',
                inbound: node.inbound,
                outId: !node.outId ? 0 : node.outId,
                rule: node.rule
            };
        }
    };
}
