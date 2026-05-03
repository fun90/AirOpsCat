import { Modal } from '/static/tabler/js/tabler.esm.min.js';

const alertApp = PetiteVue.createApp({
    loading: true,
    alerts: [],
    currentPage: 1,
    pageSize: 20,
    totalPages: 0,
    totalRecords: 0,

    filters: {
        status: '',
        alertType: '',
        resourceType: ''
    },

    stats: { active: 0, acknowledged: 0, recovered: 0 },

    acknowledgeTarget: null,
    acknowledging: false,
    deleteTarget: null,
    deleting: false,

    _acknowledgeModal: null,
    _deleteModal: null,

    initialize() {
        this.refreshAlerts();
    },

    ensureModals() {
        const acknowledgeModalElement = document.getElementById('alert-acknowledge-modal');
        const deleteModalElement = document.getElementById('alert-delete-modal');
        if (!this._acknowledgeModal && acknowledgeModalElement) {
            this._acknowledgeModal = Modal.getOrCreateInstance(acknowledgeModalElement);
        }
        if (!this._deleteModal && deleteModalElement) {
            this._deleteModal = Modal.getOrCreateInstance(deleteModalElement);
        }
    },

    async refreshAlerts() {
        await this.loadAlerts();
    },

    onFilterChange() {
        this.currentPage = 1;
        this.loadAlerts();
    },

    resetFilters() {
        this.filters = { status: '', alertType: '', resourceType: '' };
        this.currentPage = 1;
        this.loadAlerts();
    },

    async loadAlerts() {
        this.loading = true;
        try {
            const params = new URLSearchParams({
                page: this.currentPage,
                size: this.pageSize,
                ...(this.filters.status && { status: this.filters.status }),
                ...(this.filters.alertType && { alertType: this.filters.alertType }),
                ...(this.filters.resourceType && { resourceType: this.filters.resourceType })
            });
            const res = await fetch(`/api/alert-states?${params}`);
            if (!res.ok) throw new Error(await this.errorMessage(res, '加载告警列表失败'));
            const data = await res.json();
            this.alerts = data.records || [];
            this.totalRecords = data.total || 0;
            this.totalPages = data.pages || 0;
            if (data.stats) {
                this.stats.active = data.stats.active || 0;
                this.stats.acknowledged = data.stats.acknowledged || 0;
                this.stats.recovered = data.stats.recovered || 0;
            } else {
                this.stats.active = this.alerts.filter(alert => alert.status === 'ACTIVE').length;
                this.stats.acknowledged = this.alerts.filter(alert => alert.status === 'ACKNOWLEDGED').length;
                this.stats.recovered = this.alerts.filter(alert => alert.status === 'RECOVERED').length;
            }
        } catch (e) {
            ToastUtils.show('错误', e.message, 'danger');
        } finally {
            this.loading = false;
        }
    },

    async loadStats() {
        try {
            const res = await fetch('/api/alert-states/stats');
            if (!res.ok) throw new Error(await this.errorMessage(res, '加载告警统计失败'));
            const stats = await res.json();
            this.stats.active = stats.active || 0;
            this.stats.acknowledged = stats.acknowledged || 0;
            this.stats.recovered = stats.recovered || 0;
        } catch (e) {
            // stats 加载失败不影响主列表
        }
    },

    openAcknowledge(alert) {
        this.acknowledgeTarget = alert;
        this.ensureModals();
        if (!this._acknowledgeModal) {
            ToastUtils.show('错误', '确认弹窗初始化失败', 'danger');
            return;
        }
        this._acknowledgeModal.show();
    },

    async confirmAcknowledge() {
        if (!this.acknowledgeTarget) return;
        this.acknowledging = true;
        try {
            const res = await fetch(`/api/alert-states/${this.acknowledgeTarget.id}/acknowledge`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            if (res.status === 409) {
                const text = await res.text();
                throw new Error(text || '告警已不处于 ACTIVE 状态');
            }
            if (!res.ok) throw new Error(await this.errorMessage(res, '确认告警失败'));
            this._acknowledgeModal.hide();
            ToastUtils.show('成功', '告警已确认', 'success');
            await this.loadAlerts();
        } catch (e) {
            ToastUtils.show('错误', e.message, 'danger');
        } finally {
            this.acknowledging = false;
        }
    },

    openDelete(alert) {
        this.deleteTarget = alert;
        this.ensureModals();
        if (!this._deleteModal) {
            ToastUtils.show('错误', '清除弹窗初始化失败', 'danger');
            return;
        }
        this._deleteModal.show();
    },

    async confirmDelete() {
        if (!this.deleteTarget) return;
        this.deleting = true;
        try {
            const res = await fetch(`/api/alert-states/${this.deleteTarget.id}`, { method: 'DELETE' });
            if (!res.ok) throw new Error('清除告警失败');
            this._deleteModal.hide();
            ToastUtils.show('成功', '告警已清除', 'success');
            if (this.alerts.length === 1 && this.currentPage > 1) this.currentPage--;
            await this.loadAlerts();
        } catch (e) {
            ToastUtils.show('错误', e.message, 'danger');
        } finally {
            this.deleting = false;
        }
    },

    // 分页控件所需方法
    changePageSize() {
        this.currentPage = 1;
        this.loadAlerts();
    },
    prevPage() {
        if (this.currentPage > 1) { this.currentPage--; this.loadAlerts(); }
    },
    nextPage() {
        if (this.currentPage < this.totalPages) { this.currentPage++; this.loadAlerts(); }
    },
    goToPage(p) {
        this.currentPage = p; this.loadAlerts();
    },
    paginationPages() {
        const pages = [];
        const start = Math.max(1, this.currentPage - 2);
        const end = Math.min(this.totalPages, this.currentPage + 2);
        for (let i = start; i <= end; i++) pages.push(i);
        return pages;
    },

    // 格式化辅助
    formatDateTime(dt) {
        if (!dt) return '';
        return new Date(dt).toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-');
    },

    alertTypeLabel(type) {
        const map = {
            'account-expiring': '账户到期',
            'server-expiring': '服务器到期',
            'domain-expiring': '域名到期',
            'server-traffic-threshold': '服务器流量阈值',
            'server-monitor-load': '服务器负载',
            'account-traffic-over-quota': '账户流量超额',
            'account-connection-limit': '账户连接数超限'
        };
        return map[type] || type;
    },

    resourceTypeLabel(type) {
        const map = { account: '账户', server: '服务器', domain: '域名' };
        return map[type] || type;
    },

    statusLabel(status) {
        const map = { ACTIVE: '活跃', ACKNOWLEDGED: '已确认', RECOVERED: '已恢复' };
        return map[status] || status;
    },

    statusBadgeClass(status) {
        const map = {
            ACTIVE: 'bg-danger-lt',
            ACKNOWLEDGED: 'bg-warning-lt',
            RECOVERED: 'bg-success-lt'
        };
        return map[status] || 'bg-secondary-lt';
    },

    async errorMessage(res, fallback) {
        const text = await res.text();
        return text || `${fallback} (${res.status})`;
    }
}).mount('#alert-app');
