const serverInstallApp = PetiteVue.createApp({
    servers: [],
    scripts: [],
    selectedServerId: '',
    selectedScriptNames: [],
    stepStateMap: {},
    executing: false,
    loading: false,

    get selectedSteps() {
        const selectedSet = new Set(this.selectedScriptNames);
        return this.scripts
            .filter(script => selectedSet.has(script.fileName))
            .map(script => {
                const state = this.stepStateMap[script.fileName] || {};
                return {
                    ...script,
                    status: state.status || 'pending',
                    attempts: state.attempts || 0,
                    result: state.result || null
                };
            });
    },

    get canStartExecution() {
        return !this.executing && !!this.selectedServerId && this.selectedSteps.length > 0;
    },

    mounted() {
        this.refreshAll();
    },

    async refreshAll() {
        this.loading = true;
        try {
            const [serversResponse, scriptsResponse] = await Promise.all([
                fetch('/api/admin/server-installs/servers'),
                fetch('/api/admin/server-installs/scripts')
            ]);

            if (!serversResponse.ok || !scriptsResponse.ok) {
                throw new Error('加载装机页面数据失败');
            }

            this.servers = await serversResponse.json();
            const scriptsPayload = await scriptsResponse.json();
            this.scripts = scriptsPayload.records || [];
            this.reconcileStepStates();
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '加载装机页面数据失败', 'danger');
        } finally {
            this.loading = false;
        }
    },

    reconcileStepStates() {
        const nextStateMap = {};
        this.scripts.forEach(script => {
            nextStateMap[script.fileName] = this.stepStateMap[script.fileName] || {
                status: 'pending',
                attempts: 0,
                result: null
            };
        });
        this.stepStateMap = nextStateMap;
        this.selectedScriptNames = this.selectedScriptNames.filter(fileName => nextStateMap[fileName]);
    },

    selectAllScripts() {
        this.selectedScriptNames = this.scripts.map(script => script.fileName);
    },

    clearScriptSelection() {
        this.selectedScriptNames = [];
    },

    resetSelectedStepStates() {
        this.selectedSteps.forEach(step => {
            this.stepStateMap[step.fileName] = {
                status: 'pending',
                attempts: 0,
                result: null
            };
        });
    },

    async runSelectedScripts() {
        if (!this.canStartExecution) {
            if (!this.selectedServerId) {
                ToastUtils.show('Warning', '请先选择服务器', 'warning');
            }
            return;
        }

        this.resetSelectedStepStates();
        this.executing = true;
        try {
            while (true) {
                const nextIndex = this.getNextExecutableIndex();
                if (nextIndex === -1) {
                    break;
                }

                const step = this.selectedSteps[nextIndex];
                const result = await this.runStep(step.fileName);
                if (!result.success) {
                    ToastUtils.show('Warning', `${step.title} 执行失败，可修复后单步重试`, 'warning');
                    break;
                }
            }
        } finally {
            this.executing = false;
        }
    },

    async executeSingleStep(fileName) {
        const index = this.selectedSteps.findIndex(step => step.fileName === fileName);
        if (index === -1 || !this.canRunSingleStep(index)) {
            ToastUtils.show('Warning', '请按顺序执行，前一个步骤成功后才可执行下一个', 'warning');
            return;
        }

        this.executing = true;
        try {
            await this.runStep(fileName);
        } finally {
            this.executing = false;
        }
    },

    async retryStep(fileName) {
        const index = this.selectedSteps.findIndex(step => step.fileName === fileName);
        if (index === -1) {
            return;
        }
        if (!this.canRetryStep(this.selectedSteps[index], index)) {
            ToastUtils.show('Warning', '请先确保前置步骤已成功', 'warning');
            return;
        }

        this.executing = true;
        try {
            await this.runStep(fileName);
        } finally {
            this.executing = false;
        }
    },

    async runStep(fileName) {
        const currentState = this.stepStateMap[fileName] || { attempts: 0 };
        this.stepStateMap[fileName] = {
            ...currentState,
            status: 'running',
            attempts: (currentState.attempts || 0) + 1
        };

        try {
            const response = await fetch('/api/admin/server-installs/execute', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    serverId: Number(this.selectedServerId),
                    scriptName: fileName
                })
            });

            const payload = await response.json();
            if (!response.ok) {
                throw new Error(payload.message || '执行安装步骤失败');
            }

            this.stepStateMap[fileName] = {
                ...this.stepStateMap[fileName],
                status: payload.success ? 'success' : 'failed',
                result: payload
            };

            ToastUtils.show(
                payload.success ? 'Success' : 'Error',
                `${payload.stepTitle || fileName}${payload.success ? ' 执行成功' : ' 执行失败'}`,
                payload.success ? 'success' : 'danger'
            );

            return payload;
        } catch (error) {
            console.error(error);
            const fallback = {
                scriptName: fileName,
                stepTitle: fileName,
                success: false,
                exitStatus: -1,
                stdout: '',
                stderr: error.message || '执行安装步骤失败',
                durationMs: 0,
                finishedAt: new Date().toISOString()
            };

            this.stepStateMap[fileName] = {
                ...this.stepStateMap[fileName],
                status: 'failed',
                result: fallback
            };

            ToastUtils.show('Error', error.message || '执行安装步骤失败', 'danger');
            return fallback;
        }
    },

    arePreviousStepsSuccessful(index) {
        for (let i = 0; i < index; i += 1) {
            if (this.selectedSteps[i].status !== 'success') {
                return false;
            }
        }
        return true;
    },

    getNextExecutableIndex() {
        for (let i = 0; i < this.selectedSteps.length; i += 1) {
            if (this.selectedSteps[i].status !== 'success') {
                return i;
            }
        }
        return -1;
    },

    isStepLocked(index) {
        const nextIndex = this.getNextExecutableIndex();
        return nextIndex !== -1 && index > nextIndex;
    },

    canRunSingleStep(index) {
        if (this.executing || !this.selectedServerId) {
            return false;
        }
        const step = this.selectedSteps[index];
        if (!step) {
            return false;
        }
        return index === this.getNextExecutableIndex() && ['pending', 'failed'].includes(step.status);
    },

    canRetryStep(step, index) {
        return !this.executing
            && !!this.selectedServerId
            && step.status === 'failed'
            && index === this.getNextExecutableIndex()
            && this.arePreviousStepsSuccessful(index);
    },

    statusLabel(status) {
        switch (status) {
            case 'running':
                return '执行中';
            case 'success':
                return '成功';
            case 'failed':
                return '失败';
            default:
                return '待执行';
        }
    },

    statusBadgeClass(status) {
        switch (status) {
            case 'running':
                return 'bg-blue text-blue-fg';
            case 'success':
                return 'bg-green text-green-fg';
            case 'failed':
                return 'bg-red text-red-fg';
            default:
                return 'bg-secondary text-secondary-fg';
        }
    },

    stderrTitle(step) {
        return step?.result?.success ? '附加输出' : '错误输出';
    },

    stderrClass(step) {
        return step?.result?.success
            ? 'bg-warning text-dark'
            : 'bg-danger text-white';
    },

    formatDuration(durationMs) {
        if (durationMs == null) {
            return '-';
        }
        if (durationMs < 1000) {
            return `${durationMs} ms`;
        }
        return `${(durationMs / 1000).toFixed(2)} s`;
    },

    formatDateTime(dateTime) {
        if (!dateTime) {
            return null;
        }
        return new Date(dateTime).toLocaleString();
    }
});

serverInstallApp.mount('#app');
