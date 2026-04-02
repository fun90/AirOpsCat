const scheduledTaskApp = PetiteVue.createApp({
    tasksLoading: true,
    runningTask: null,
    togglingTask: null,
    tasks: [],

    get scheduledTaskCount() {
        return (this.tasks || []).filter(task => task.scheduled && !task.paused).length;
    },

    get pausedTaskCount() {
        return (this.tasks || []).filter(task => task.paused).length;
    },

    get overdueTaskCount() {
        return (this.tasks || []).filter(task => task.scheduled && task.overdue).length;
    },

    mounted() {
        this.loadTasks();
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

    async toggleTask(task) {
        const action = task.paused ? 'resume' : 'pause';
        const successMessage = task.paused ? '定时任务已恢复调度' : '定时任务已暂停调度';

        this.togglingTask = task.taskKey;
        try {
            const response = await fetch(`/api/admin/system-configs/tasks/${task.taskKey}/${action}`, {
                method: 'POST'
            });
            const payload = await response.json();
            if (!response.ok || !payload.success) {
                throw new Error(payload.message || '更新定时任务状态失败');
            }

            await this.loadTasks();
            ToastUtils.show('Success', successMessage, 'success');
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '更新定时任务状态失败', 'danger');
        } finally {
            this.togglingTask = null;
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
    }
});

scheduledTaskApp.mount('#app');
