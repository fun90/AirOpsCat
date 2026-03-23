import { DataTable } from '/static/js/common/data-table.js';
import { createResponsiveFilterMethods } from '/static/js/common/responsive-filters.js';

const DEFAULT_ROUTE_RULE_FILTERS = Object.freeze({
    coreType: '',
    enabled: ''
});

const routeRuleTable = new DataTable({
    data: {
        entityName: 'route-rules',
        modalIdPrefix: 'route-rule-',
        filters: { ...DEFAULT_ROUTE_RULE_FILTERS },
        stats: {
            total: 0,
            enabled: 0,
            disabled: 0,
            xray: 0,
            singBox: 0
        },
        servers: [],
        landingNodes: [],
        coreTypes: [],
        ruleTypes: [],
        newRuleValueJson: '',
        editRuleValueJson: '',
        newItem: {
            name: '',
            coreType: 'xray',
            ruleType: 'domain',
            outboundNodeId: '',
            enabled: 1,
            remark: '',
            serverIds: []
        }
    },
    methods: {
        initialize() {
            this.fetchServers();
            this.fetchCoreTypes();
            this.fetchRuleTypes();
            this.fetchLandingNodes(this.newItem.coreType);
        },

        fetchServers() {
            fetch('/api/admin/route-rules/servers')
                .then(response => response.json())
                .then(data => {
                    this.servers = data;
                });
        },

        fetchCoreTypes() {
            fetch('/api/admin/route-rules/core-types')
                .then(response => response.json())
                .then(data => {
                    this.coreTypes = data;
                });
        },

        fetchRuleTypes() {
            fetch('/api/admin/route-rules/types')
                .then(response => response.json())
                .then(data => {
                    this.ruleTypes = data;
                });
        },

        fetchLandingNodes(coreType) {
            fetch(`/api/admin/route-rules/landing-nodes?coreType=${encodeURIComponent(coreType || '')}`)
                .then(response => response.json())
                .then(data => {
                    this.landingNodes = data;
                });
        },

        onCreateCoreTypeChange() {
            this.newItem.outboundNodeId = '';
            this.fetchLandingNodes(this.newItem.coreType);
        },

        onEditCoreTypeChange() {
            this.editedItem.outboundNodeId = '';
            this.fetchLandingNodes(this.editedItem.coreType);
        },

        getCoreBadgeClass(coreType) {
            return coreType === 'sing-box' ? 'bg-indigo text-white' : 'bg-blue text-white';
        },

        getRuleTypeLabel(ruleType) {
            const matched = this.ruleTypes.find(item => item.value === ruleType);
            return matched ? matched.label : ruleType;
        },

        summarizeRuleValue(ruleValue) {
            if (ruleValue == null) return '-';
            const text = JSON.stringify(ruleValue);
            return text.length > 80 ? text.slice(0, 80) + '...' : text;
        },

        formatLandingNode(node) {
            return `${node.name || ('节点#' + node.id)} (${node.serverHost || node.serverIp || '-'})`;
        },

        getRuleValueHint(ruleType, coreType) {
            if (ruleType === 'custom') {
                return `请输入完整 JSON 对象，系统会自动补齐 ${coreType === 'sing-box' ? 'outbound' : 'outboundTag'}。`;
            }
            if (ruleType === 'domain') {
                return '示例: ["geosite:netflix"] 或 ["example.com"]';
            }
            if (ruleType === 'ip' || ruleType === 'source_ip') {
                return '示例: ["1.1.1.1/32", "8.8.8.0/24"]';
            }
            if (ruleType === 'port') {
                return '示例: "443" 或 ["80", "443"]';
            }
            return '请输入合法 JSON，字段名由规则类型自动映射。';
        },

        formatCreateRuleValue() {
            this.newRuleValueJson = this.formatJsonString(this.newRuleValueJson);
        },

        formatEditRuleValue() {
            this.editRuleValueJson = this.formatJsonString(this.editRuleValueJson);
        },

        formatJsonString(value) {
            try {
                return JSON.stringify(JSON.parse(value), null, 2);
            } catch (error) {
                ToastUtils.show('Error', 'JSON 格式错误，无法格式化', 'danger');
                return value;
            }
        },

        validateCreateForm() {
            return this.validateForm(this.newItem, this.newRuleValueJson);
        },

        validateEditForm() {
            return this.validateForm(this.editedItem, this.editRuleValueJson);
        },

        validateForm(item, ruleValueJson) {
            this.validationErrors = {};

            if (!item.name || !item.name.trim()) this.validationErrors.name = '请输入规则名称';
            if (!item.coreType) this.validationErrors.coreType = '请选择内核类型';
            if (!item.ruleType) this.validationErrors.ruleType = '请选择规则类型';
            if (!item.serverIds || item.serverIds.length === 0) this.validationErrors.serverIds = '请选择至少一个服务器';
            if (!item.outboundNodeId) this.validationErrors.outboundNodeId = '请选择出站落地节点';
            if (!ruleValueJson || !ruleValueJson.trim()) {
                this.validationErrors.ruleValue = '请输入规则值';
            } else {
                try {
                    const parsed = JSON.parse(ruleValueJson);
                    if (item.ruleType === 'custom' && (Array.isArray(parsed) || parsed === null || typeof parsed !== 'object')) {
                        this.validationErrors.ruleValue = '自定义规则必须为 JSON 对象';
                    }
                } catch (error) {
                    this.validationErrors.ruleValue = '规则值不是合法 JSON';
                }
            }

            return Object.keys(this.validationErrors).length === 0;
        },

        prepareCreateData() {
            return this.prepareData(this.newItem, this.newRuleValueJson);
        },

        prepareUpdateData() {
            return this.prepareData(this.editedItem, this.editRuleValueJson);
        },

        prepareData(item, ruleValueJson) {
            try {
                return {
                    name: item.name,
                    coreType: item.coreType,
                    ruleType: item.ruleType,
                    ruleValue: JSON.parse(ruleValueJson),
                    outboundNodeId: Number(item.outboundNodeId),
                    enabled: item.enabled === 1 ? 1 : 0,
                    remark: item.remark || null,
                    serverIds: (item.serverIds || []).map(Number)
                };
            } catch (error) {
                ToastUtils.show('Error', '规则值 JSON 解析失败: ' + error.message, 'danger');
                return null;
            }
        },

        resetCreateForm() {
            this.newItem = {
                name: '',
                coreType: 'xray',
                ruleType: 'domain',
                outboundNodeId: '',
                enabled: 1,
                remark: '',
                serverIds: []
            };
            this.newRuleValueJson = '[\n  "geosite:netflix"\n]';
            this.fetchLandingNodes(this.newItem.coreType);
        },

        prepareEditForm(item) {
            this.editRuleValueJson = JSON.stringify(item.ruleValue, null, 2);
            this.fetchLandingNodes(item.coreType);
            return {
                id: item.id,
                name: item.name,
                coreType: item.coreType,
                ruleType: item.ruleType,
                outboundNodeId: item.outboundNodeId,
                enabled: item.enabled,
                remark: item.remark || '',
                serverIds: (item.serverIds || []).map(Number)
            };
        },

        getToggleStatusUrl(item, action) {
            return `/api/admin/route-rules/${item.id}/${action}`;
        },

        updateItemStatus(item, data) {
            const index = this.records.findIndex(record => record.id === item.id);
            if (index !== -1) {
                this.records[index].enabled = data.enabled;
            }
            this.fetchRecords();
        },

        ...createResponsiveFilterMethods({
            createDefaultFilters: () => ({ ...DEFAULT_ROUTE_RULE_FILTERS }),
            getActiveTags() {
                const tags = this.searchQuery ? [{
                    key: 'search',
                    label: '搜索',
                    value: this.searchQuery
                }] : [];

                if (this.filters.coreType) {
                    const label = this.coreTypes.find(item => item.value === this.filters.coreType)?.label || this.filters.coreType;
                    tags.push({ key: 'coreType', label: '内核', value: label });
                }

                if (this.filters.enabled !== '') {
                    tags.push({ key: 'enabled', label: '状态', value: this.filters.enabled === 'true' ? '已启用' : '已禁用' });
                }

                return tags;
            }
        })
    }
});

routeRuleTable.createApp('#app');
