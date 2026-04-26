const serverMaintenanceApp = PetiteVue.createApp({
    servers: [],
    scripts: [],
    selectedServerIds: [],
    selectedScriptNames: [],
    // { [serverId]: { [fileName]: { status, attempts, result } } }
    serverStepStateMap: {},
    executing: false,
    loading: false,
    loadingPreview: false,
    savingScript: false,
    previewContent: '',
    previewScriptName: '',
    editMode: false,
    editContent: '',

    get selectedServers() {
        const selectedSet = new Set(this.selectedServerIds.map(String));
        return this.servers.filter(s => selectedSet.has(String(s.id)));
    },

    get selectedSteps() {
        const selectedSet = new Set(this.selectedScriptNames);
        return this.scripts
            .filter(script => selectedSet.has(script.fileName))
            .map(script => ({ ...script }));
    },

    get canStartExecution() {
        return !this.executing && this.selectedServerIds.length > 0 && this.selectedSteps.length > 0;
    },

    get canResumeExecution() {
        if (this.executing || this.selectedServerIds.length === 0 || this.selectedSteps.length === 0) {
            return false;
        }
        // 至少有一台服务器存在未完成（非 success）的步骤
        return this.selectedServers.some(server =>
            this.selectedSteps.some(step => {
                const s = this.getStepState(server.id, step.fileName);
                return s.status !== 'success';
            })
        );
    },

    get progressPercent() {
        const totalServers = this.selectedServers.length;
        const totalSteps = this.selectedSteps.length;
        if (!totalServers || !totalSteps) {
            return 0;
        }
        let completed = 0;
        let running = 0;
        for (const server of this.selectedServers) {
            const stateMap = this.serverStepStateMap[String(server.id)] || {};
            for (const step of this.selectedSteps) {
                const s = stateMap[step.fileName];
                if (s && s.status === 'success') {
                    completed += 1;
                } else if (s && s.status === 'running') {
                    running += 1;
                }
            }
        }
        return Math.min(100, ((completed + running * 0.5) / (totalServers * totalSteps)) * 100);
    },

    getStepState(serverId, fileName) {
        const stateMap = this.serverStepStateMap[String(serverId)] || {};
        return stateMap[fileName] || { status: 'pending', attempts: 0, result: null };
    },

    setStepState(serverId, fileName, patch) {
        const sid = String(serverId);
        if (!this.serverStepStateMap[sid]) {
            this.serverStepStateMap[sid] = {};
        }
        const current = this.serverStepStateMap[sid][fileName] || { status: 'pending', attempts: 0, result: null };
        this.serverStepStateMap[sid][fileName] = { ...current, ...patch };
        // 触发响应式更新
        this.serverStepStateMap = { ...this.serverStepStateMap };
    },

    mounted() {
        this.refreshAll();
    },

    async refreshAll() {
        this.loading = true;
        try {
            const [serversResponse, scriptsResponse] = await Promise.all([
                fetch('/api/admin/server-maintenance/servers'),
                fetch('/api/admin/server-maintenance/scripts')
            ]);

            if (!serversResponse.ok || !scriptsResponse.ok) {
                throw new Error('加载运维作业数据失败');
            }

            this.servers = await serversResponse.json();
            const scriptsPayload = await scriptsResponse.json();
            this.scripts = scriptsPayload.records || [];
            this.reconcileStepStates();
            this.loadFromQueryParams();
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '加载运维作业数据失败', 'danger');
        } finally {
            this.loading = false;
        }
    },

    loadFromQueryParams() {
        const urlParams = new URLSearchParams(window.location.search);
        const serverId = urlParams.get('serverId');
        if (serverId) {
            this.selectedServerIds = [serverId];
        }
    },

    reconcileStepStates() {
        const nextStateMap = {};
        for (const [sid, stateMap] of Object.entries(this.serverStepStateMap)) {
            nextStateMap[sid] = {};
            this.scripts.forEach(script => {
                nextStateMap[sid][script.fileName] = stateMap[script.fileName] || {
                    status: 'pending',
                    attempts: 0,
                    result: null
                };
            });
        }
        this.serverStepStateMap = nextStateMap;
        this.selectedScriptNames = this.selectedScriptNames.filter(
            fileName => this.scripts.some(s => s.fileName === fileName)
        );
        this.selectedServerIds = this.selectedServerIds.filter(
            id => this.servers.some(s => String(s.id) === String(id))
        );
    },

    selectAllServers() {
        this.selectedServerIds = this.servers.map(s => String(s.id));
    },

    clearServerSelection() {
        this.selectedServerIds = [];
    },

    toggleServer(id) {
        const sid = String(id);
        const idx = this.selectedServerIds.indexOf(sid);
        if (idx === -1) {
            this.selectedServerIds = [...this.selectedServerIds, sid];
        } else {
            this.selectedServerIds = this.selectedServerIds.filter(x => x !== sid);
        }
    },

    isServerSelected(id) {
        return this.selectedServerIds.includes(String(id));
    },

    selectAllScripts() {
        this.selectedScriptNames = this.scripts.map(script => script.fileName);
    },

    clearScriptSelection() {
        this.selectedScriptNames = [];
    },

    resetSelectedStepStates() {
        const nextStateMap = { ...this.serverStepStateMap };
        for (const server of this.selectedServers) {
            const sid = String(server.id);
            nextStateMap[sid] = nextStateMap[sid] ? { ...nextStateMap[sid] } : {};
            this.selectedSteps.forEach(step => {
                nextStateMap[sid][step.fileName] = { status: 'pending', attempts: 0, result: null };
            });
        }
        this.serverStepStateMap = nextStateMap;
    },

    async previewScript(scriptName) {
        if (this.loadingPreview) {
            return;
        }
        this.editMode = false;
        this.loadingPreview = true;
        try {
            const response = await fetch(`/api/admin/server-maintenance/scripts/${encodeURIComponent(scriptName)}/preview`);
            if (!response.ok) {
                const payload = await response.json();
                throw new Error(payload.message || '加载脚本内容失败');
            }
            const payload = await response.json();
            this.previewContent = payload.content || '';
            this.previewScriptName = scriptName;
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '加载脚本内容失败', 'danger');
        } finally {
            this.loadingPreview = false;
        }
    },

    closePreview() {
        this.previewContent = '';
        this.previewScriptName = '';
        this.editMode = false;
        this.editContent = '';
    },

    startEdit() {
        this.editContent = this.previewContent;
        this.editMode = true;
    },

    cancelEdit() {
        this.editMode = false;
        this.editContent = '';
    },

    async saveScript() {
        if (this.savingScript || !this.previewScriptName) {
            return;
        }
        this.savingScript = true;
        try {
            const response = await fetch(`/api/admin/server-maintenance/scripts/${encodeURIComponent(this.previewScriptName)}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content: this.editContent })
            });
            const payload = await response.json();
            if (!response.ok) {
                throw new Error(payload.message || '保存脚本失败');
            }
            this.previewContent = this.editContent;
            this.editMode = false;
            this.editContent = '';
            ToastUtils.show('Success', '脚本保存成功', 'success');
            // 刷新脚本列表（元信息可能已变更）
            const scriptsResponse = await fetch('/api/admin/server-maintenance/scripts');
            if (scriptsResponse.ok) {
                const scriptsPayload = await scriptsResponse.json();
                this.scripts = scriptsPayload.records || [];
            }
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '保存脚本失败', 'danger');
        } finally {
            this.savingScript = false;
        }
    },

    // ─── 批量执行 ──────────────────────────────────────────────

    async runSelectedScripts() {
        if (!this.canStartExecution) {
            if (this.selectedServerIds.length === 0) {
                ToastUtils.show('Warning', '请先选择服务器', 'warning');
            } else if (this.selectedSteps.length === 0) {
                ToastUtils.show('Warning', '请先选择脚本', 'warning');
            }
            return;
        }

        this.resetSelectedStepStates();
        this.executing = true;
        try {
            // 所有服务器并发启动，每台服务器内部顺序执行
            await Promise.all(
                this.selectedServers.map(server => this.runServerSteps(server))
            );
        } finally {
            this.executing = false;
        }
    },

    async runServerSteps(server) {
        for (const step of this.selectedSteps) {
            const result = await this.runStep(server, step.fileName);
            if (!result.success) {
                ToastUtils.show('Warning', `[${server.name}] ${step.title} 执行失败，该服务器已停止`, 'warning');
                break;
            }
        }
    },

    async resumeExecution() {
        if (!this.canResumeExecution) return;
        this.executing = true;
        try {
            await Promise.all(
                this.selectedServers.map(server => this.resumeServerSteps(server))
            );
        } finally {
            this.executing = false;
        }
    },

    async resumeServerSteps(server) {
        // 从第一个非 success 的步骤开始
        for (const step of this.selectedSteps) {
            const s = this.getStepState(server.id, step.fileName);
            if (s.status === 'success') continue;
            const result = await this.runStep(server, step.fileName);
            if (!result.success) {
                ToastUtils.show('Warning', `[${server.name}] ${step.title} 执行失败，该服务器已停止`, 'warning');
                break;
            }
        }
    },

    async executeSingleStep(serverId, fileName) {
        const server = this.servers.find(s => String(s.id) === String(serverId));
        if (!server) return;
        const index = this.selectedSteps.findIndex(step => step.fileName === fileName);
        if (index === -1 || !this.canRunSingleStep(serverId, index)) {
            ToastUtils.show('Warning', '请按顺序执行，前一个步骤成功后才可执行下一个', 'warning');
            return;
        }
        this.executing = true;
        try {
            await this.runStep(server, fileName);
        } finally {
            this.executing = false;
        }
    },

    async retryStep(serverId, fileName) {
        const server = this.servers.find(s => String(s.id) === String(serverId));
        if (!server) return;
        const index = this.selectedSteps.findIndex(step => step.fileName === fileName);
        if (index === -1) return;
        const step = this.selectedSteps[index];
        if (!this.canRetryStep(serverId, step, index)) {
            ToastUtils.show('Warning', '请先确保前置步骤已成功', 'warning');
            return;
        }
        this.executing = true;
        try {
            await this.runStep(server, fileName);
        } finally {
            this.executing = false;
        }
    },

    async runStep(server, fileName) {
        const current = this.getStepState(server.id, fileName);
        this.setStepState(server.id, fileName, {
            status: 'running',
            attempts: (current.attempts || 0) + 1
        });

        try {
            const response = await fetch('/api/admin/server-maintenance/execute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    serverId: Number(server.id),
                    scriptName: fileName
                })
            });

            const payload = await response.json();
            if (!response.ok) {
                throw new Error(payload.message || '执行运维步骤失败');
            }

            this.setStepState(server.id, fileName, {
                status: payload.success ? 'success' : 'failed',
                result: payload
            });

            return payload;
        } catch (error) {
            console.error(error);
            const fallback = {
                scriptName: fileName,
                stepTitle: fileName,
                success: false,
                exitStatus: -1,
                stdout: '',
                stderr: error.message || '执行运维步骤失败',
                durationMs: 0,
                finishedAt: new Date().toISOString()
            };
            this.setStepState(server.id, fileName, {
                status: 'failed',
                result: fallback
            });
            return fallback;
        }
    },

    // ─── 状态辅助 ───────────────────────────────────────────────

    arePreviousStepsSuccessful(serverId, index) {
        for (let i = 0; i < index; i++) {
            const s = this.getStepState(serverId, this.selectedSteps[i].fileName);
            if (s.status !== 'success') return false;
        }
        return true;
    },

    getNextExecutableIndex(serverId) {
        for (let i = 0; i < this.selectedSteps.length; i++) {
            const s = this.getStepState(serverId, this.selectedSteps[i].fileName);
            if (s.status !== 'success') return i;
        }
        return -1;
    },

    isStepLocked(serverId, index) {
        const nextIndex = this.getNextExecutableIndex(serverId);
        return nextIndex !== -1 && index > nextIndex;
    },

    canRunSingleStep(serverId, index) {
        if (this.executing || !serverId) return false;
        const step = this.selectedSteps[index];
        if (!step) return false;
        const s = this.getStepState(serverId, step.fileName);
        return index === this.getNextExecutableIndex(serverId) && ['pending', 'failed'].includes(s.status);
    },

    canRetryStep(serverId, step, index) {
        const s = this.getStepState(serverId, step.fileName);
        return !this.executing
            && s.status === 'failed'
            && index === this.getNextExecutableIndex(serverId)
            && this.arePreviousStepsSuccessful(serverId, index);
    },

    stepItemClass(serverId, fileName) {
        const s = this.getStepState(serverId, fileName);
        if (s.status === 'running') return 'maintenance-step-running';
        if (s.status === 'success') return 'maintenance-step-success';
        if (s.status === 'failed') return 'maintenance-step-failed';
        return 'maintenance-step-pending';
    },

    statusLabel(status) {
        switch (status) {
            case 'running': return '执行中';
            case 'success': return '成功';
            case 'failed': return '失败';
            default: return '待执行';
        }
    },

    statusBadgeClass(status) {
        switch (status) {
            case 'running': return 'bg-blue text-blue-fg';
            case 'success': return 'bg-green text-green-fg';
            case 'failed': return 'bg-red text-red-fg';
            default: return 'bg-secondary text-secondary-fg';
        }
    },

    serverSummaryBadge(serverId) {
        const stateMap = this.serverStepStateMap[String(serverId)] || {};
        const steps = this.selectedSteps;
        if (!steps.length) return 'bg-secondary text-secondary-fg';
        const allSuccess = steps.every(s => (stateMap[s.fileName] || {}).status === 'success');
        const anyFailed = steps.some(s => (stateMap[s.fileName] || {}).status === 'failed');
        const anyRunning = steps.some(s => (stateMap[s.fileName] || {}).status === 'running');
        if (allSuccess) return 'bg-green text-green-fg';
        if (anyFailed) return 'bg-red text-red-fg';
        if (anyRunning) return 'bg-blue text-blue-fg';
        return 'bg-secondary text-secondary-fg';
    },

    serverSummaryLabel(serverId) {
        const stateMap = this.serverStepStateMap[String(serverId)] || {};
        const steps = this.selectedSteps;
        if (!steps.length) return '等待';
        const successCount = steps.filter(s => (stateMap[s.fileName] || {}).status === 'success').length;
        const failedCount = steps.filter(s => (stateMap[s.fileName] || {}).status === 'failed').length;
        const runningCount = steps.filter(s => (stateMap[s.fileName] || {}).status === 'running').length;
        if (successCount === steps.length) return '全部成功';
        if (failedCount > 0) return `${failedCount} 步失败`;
        if (runningCount > 0) return '执行中';
        return `${successCount}/${steps.length}`;
    },

    stderrTitle(result) {
        return result?.success ? '附加输出' : '错误输出';
    },

    stderrClass(result) {
        return result?.success ? 'bg-warning text-dark' : 'bg-danger text-white';
    },

    formatDuration(durationMs) {
        if (durationMs == null) return '-';
        if (durationMs < 1000) return `${durationMs} ms`;
        return `${(durationMs / 1000).toFixed(2)} s`;
    },

    formatDateTime(dateTime) {
        if (!dateTime) return null;
        return new Date(dateTime).toLocaleString();
    }
});

serverMaintenanceApp.mount('#app');
