const systemConfigApp = PetiteVue.createApp({
    loading: true,
    savingGroup: null,
    testingGroup: null,
    groups: [],
    originalGroups: {},
    currentValues: {},
    showSensitive: {},

    get totalItems() {
        return this.groups.reduce((sum, group) => sum + (group.items?.length || 0), 0);
    },

    mounted() {
        this.loadGroups();
    },

    async loadGroups() {
        this.loading = true;
        try {
            const response = await fetch('/api/admin/system-configs');
            const payload = await response.json();
            if (!response.ok || !payload.success) {
                throw new Error(payload.message || '加载系统配置失败');
            }

            this.groups = payload.data || [];
            this.syncGroupState(this.groups);
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '加载系统配置失败', 'danger');
        } finally {
            this.loading = false;
        }
    },

    syncGroupState(groups) {
        this.originalGroups = {};
        this.currentValues = {};

        (groups || []).forEach(group => {
            this.originalGroups[group.groupKey] = {};
            this.currentValues[group.groupKey] = {};

            (group.items || []).forEach(item => {
                const normalizedValue = item.inputType === 'checkbox'
                    ? String(item.value === 'true')
                    : (item.value || '');
                this.originalGroups[group.groupKey][item.key] = normalizedValue;
                this.currentValues[group.groupKey][item.key] = normalizedValue;
            });
        });
    },

    updateField(groupKey, itemKey, value) {
        this.currentValues[groupKey][itemKey] = value;
    },

    updateCheckbox(groupKey, itemKey, event) {
        this.currentValues[groupKey][itemKey] = String(event.target.checked);
    },

    toBoolean(value) {
        return String(value) === 'true';
    },

    resolveInputType(item) {
        if (item.sensitive) {
            return this.showSensitive[item.key] ? 'text' : 'password';
        }
        if (item.inputType === 'number') {
            return 'number';
        }
        if (item.inputType === 'url') {
            return 'url';
        }
        return 'text';
    },

    toggleSensitive(itemKey) {
        this.showSensitive[itemKey] = !this.showSensitive[itemKey];
    },

    isGroupDirty(groupKey) {
        const current = this.currentValues[groupKey] || {};
        const original = this.originalGroups[groupKey] || {};
        return Object.keys(current).some(key => (current[key] || '') !== (original[key] || ''));
    },

    resetGroup(groupKey) {
        this.currentValues[groupKey] = { ...(this.originalGroups[groupKey] || {}) };
    },

    buildPayload(groupKey) {
        return {
            values: { ...(this.currentValues[groupKey] || {}) }
        };
    },

    async saveGroup(groupKey) {
        this.savingGroup = groupKey;
        try {
            const response = await fetch(`/api/admin/system-configs/${groupKey}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(this.buildPayload(groupKey))
            });
            const payload = await response.json();
            if (!response.ok || !payload.success) {
                throw new Error(payload.message || '保存配置失败');
            }

            const savedGroup = payload.data;
            this.groups = this.groups.map(group => group.groupKey === groupKey ? savedGroup : group);
            this.syncGroupState(this.groups);
            ToastUtils.show('Success', '配置保存成功', 'success');
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '保存配置失败', 'danger');
        } finally {
            this.savingGroup = null;
        }
    },

    async testGroup(groupKey) {
        if (groupKey !== 'bark') {
            return;
        }

        this.testingGroup = groupKey;
        try {
            const response = await fetch('/api/admin/system-configs/bark/test', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    values: { ...(this.currentValues[groupKey] || {}) },
                    title: 'AirOpsCat测试',
                    body: '这是一条来自系统配置中心的测试通知。'
                })
            });
            const payload = await response.json();
            if (!response.ok || !payload.success) {
                throw new Error(payload.message || 'Bark 测试失败');
            }

            ToastUtils.show('Success', 'Bark 测试通知已发送', 'success');
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || 'Bark 测试失败', 'danger');
        } finally {
            this.testingGroup = null;
        }
    },

    scrollToGroup(groupKey) {
        const element = document.getElementById(`group-${groupKey}`);
        if (element) {
            element.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    }
});

systemConfigApp.mount('#app');
