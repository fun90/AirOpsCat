const systemConfigApp = PetiteVue.createApp({
    loading: true,
    tasksLoading: true,
    savingGroup: null,
    testingGroup: null,
    runningTask: null,
    groups: [],
    tasks: [],
    originalGroups: {},
    currentValues: {},
    showSensitive: {},
    expandedGroups: {},
    searchQuery: '',

    get totalItems() {
        return this.groups.reduce((sum, group) => sum + (group.items?.length || 0), 0);
    },

    get normalizedSearchQuery() {
        return this.normalizeText(this.searchQuery);
    },

    get filteredGroups() {
        const query = this.normalizedSearchQuery;
        return (this.groups || []).reduce((result, group) => {
            const items = group.items || [];
            const matchAllItems = !query || this.matchesGroup(group, query);
            const visibleItems = matchAllItems
                ? items
                : items.filter(item => this.matchesItem(item, query));

            if (visibleItems.length) {
                result.push({
                    ...group,
                    visibleItems
                });
            }
            return result;
        }, []);
    },

    get filteredItemCount() {
        return this.filteredGroups.reduce((sum, group) => sum + this.getVisibleItemCount(group), 0);
    },

    get dirtyGroupsCount() {
        return (this.groups || []).filter(group => this.isGroupDirty(group.groupKey)).length;
    },

    get scheduledTaskCount() {
        return (this.tasks || []).filter(task => task.scheduled).length;
    },

    get overdueTaskCount() {
        return (this.tasks || []).filter(task => task.overdue).length;
    },

    mounted() {
        this.loadData();
    },

    async loadData() {
        await Promise.all([
            this.loadGroups(),
            this.loadTasks()
        ]);
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

    async loadTasks() {
        this.tasksLoading = true;
        try {
            const response = await fetch('/api/admin/system-configs/tasks');
            const payload = await response.json();
            if (!response.ok || !payload.success) {
                throw new Error(payload.message || '加载定时任务失败');
            }

            this.tasks = payload.data || [];
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '加载定时任务失败', 'danger');
        } finally {
            this.tasksLoading = false;
        }
    },

    syncGroupState(groups) {
        this.originalGroups = {};
        this.currentValues = {};
        const nextExpandedGroups = {};

        (groups || []).forEach(group => {
            this.originalGroups[group.groupKey] = {};
            this.currentValues[group.groupKey] = {};
            nextExpandedGroups[group.groupKey] = this.expandedGroups[group.groupKey] ?? false;

            (group.items || []).forEach(item => {
                const normalizedValue = item.inputType === 'checkbox'
                    ? String(item.value === 'true')
                    : (item.value || '');
                this.originalGroups[group.groupKey][item.key] = normalizedValue;
                this.currentValues[group.groupKey][item.key] = normalizedValue;
            });
        });

        if (!(groups || []).every(group => this.expandedGroups[group.groupKey] !== undefined) && groups?.length) {
            nextExpandedGroups[groups[0].groupKey] = true;
        }

        this.expandedGroups = nextExpandedGroups;
    },

    updateField(groupKey, itemKey, value) {
        this.currentValues[groupKey][itemKey] = value;
    },

    updateCheckbox(groupKey, itemKey, event) {
        this.currentValues[groupKey][itemKey] = String(event.target.checked);
    },

    getCurrentValue(groupKey, itemKey) {
        return this.currentValues[groupKey]?.[itemKey] || '';
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

    normalizeText(value) {
        return String(value || '').trim().toLowerCase();
    },

    matchesGroup(group, query) {
        const haystack = [
            group.groupKey,
            group.title,
            group.description
        ].map(value => this.normalizeText(value)).join(' ');
        return haystack.includes(query);
    },

    matchesItem(item, query) {
        const haystack = [
            item.key,
            item.label,
            item.description,
            item.placeholder
        ].map(value => this.normalizeText(value)).join(' ');
        return haystack.includes(query);
    },

    getVisibleItemCount(group) {
        return group.visibleItems?.length || group.items?.length || 0;
    },

    isGroupExpanded(groupKey) {
        if (this.normalizedSearchQuery) {
            return true;
        }
        return !!this.expandedGroups[groupKey];
    },

    toggleGroup(groupKey) {
        if (this.normalizedSearchQuery) {
            return;
        }
        this.expandedGroups[groupKey] = !this.expandedGroups[groupKey];
    },

    expandAllVisible() {
        this.filteredGroups.forEach(group => {
            this.expandedGroups[group.groupKey] = true;
        });
    },

    collapseAllVisible() {
        if (this.normalizedSearchQuery) {
            return;
        }
        this.filteredGroups.forEach(group => {
            this.expandedGroups[group.groupKey] = false;
        });
    },

    clearSearch() {
        this.searchQuery = '';
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
            await this.loadTasks();
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

    async runTask(taskKey) {
        this.runningTask = taskKey;
        try {
            const response = await fetch(`/api/admin/system-configs/tasks/${taskKey}/run`, {
                method: 'POST'
            });
            const payload = await response.json();
            if (!response.ok || !payload.success) {
                throw new Error(payload.message || '执行定时任务失败');
            }

            await this.loadTasks();
            ToastUtils.show('Success', '定时任务执行完成', 'success');
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '执行定时任务失败', 'danger');
        } finally {
            this.runningTask = null;
        }
    },

    formatDateTime(value) {
        if (!value) {
            return '暂无';
        }

        const normalized = value.replace(' ', 'T');
        const date = new Date(normalized);
        if (Number.isNaN(date.getTime())) {
            return value;
        }
        return date.toLocaleString('zh-CN', {
            hour12: false
        });
    },

    scrollToGroup(groupKey) {
        this.expandedGroups[groupKey] = true;
        const element = document.getElementById(`group-${groupKey}`);
        if (element) {
            element.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    }
});

systemConfigApp.mount('#app');
