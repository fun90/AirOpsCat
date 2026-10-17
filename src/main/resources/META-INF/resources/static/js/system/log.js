const LEVELS = {
    INFO: { label: '信息', badge: 'bg-info-lt' },
    WARN: { label: '警告', badge: 'bg-warning-lt' },
    ERROR: { label: '错误', badge: 'bg-danger-lt' }
};

PetiteVue.createApp({
    loading: true,
    logs: [],
    categories: [],
    expandedId: null,
    currentPage: 1,
    pageSize: 20,
    totalPages: 0,
    totalRecords: 0,
    filters: { category: '', level: '', keyword: '', startTime: '', endTime: '' },

    async initialize() {
        await this.loadCategories();
        await this.loadLogs();
    },

    async loadCategories() {
        try {
            const response = await fetch('/api/admin/system-logs/categories');
            const payload = await response.json();
            this.categories = payload.data || [];
        } catch (error) {
            ToastUtils.show('错误', '加载日志分类失败', 'danger');
        }
    },

    async loadLogs() {
        this.loading = true;
        this.expandedId = null;
        try {
            const params = new URLSearchParams({ page: this.currentPage, size: this.pageSize });
            Object.entries(this.filters).forEach(([key, value]) => {
                if (value) params.set(key, value);
            });
            const response = await fetch(`/api/admin/system-logs?${params}`);
            const payload = await response.json();
            if (!response.ok || !payload.success) {
                throw new Error(payload.message || '加载系统日志失败');
            }
            const page = payload.data || {};
            this.logs = page.records || [];
            this.totalRecords = page.total || 0;
            this.totalPages = page.pages || 0;
            this.currentPage = page.current || this.currentPage;
            this.pageSize = page.size || this.pageSize;
        } catch (error) {
            ToastUtils.show('错误', error.message || '加载系统日志失败', 'danger');
        } finally {
            this.loading = false;
        }
    },

    refresh() {
        return this.loadLogs();
    },

    search() {
        this.currentPage = 1;
        return this.loadLogs();
    },

    selectCategory(category) {
        this.filters.category = category;
        return this.search();
    },

    resetFilters() {
        this.filters = { category: this.filters.category, level: '', keyword: '', startTime: '', endTime: '' };
        return this.search();
    },

    toggleDetail(id) {
        this.expandedId = this.expandedId === id ? null : id;
    },

    changePageSize() {
        this.currentPage = 1;
        this.loadLogs();
    },

    prevPage() {
        if (this.currentPage > 1) {
            this.currentPage--;
            this.loadLogs();
        }
    },

    nextPage() {
        if (this.currentPage < this.totalPages) {
            this.currentPage++;
            this.loadLogs();
        }
    },

    goToPage(page) {
        this.currentPage = page;
        this.loadLogs();
    },

    paginationPages() {
        const pages = [];
        const start = Math.max(1, this.currentPage - 2);
        const end = Math.min(this.totalPages, this.currentPage + 2);
        for (let i = start; i <= end; i++) pages.push(i);
        return pages;
    },

    categoryLabel(value) {
        return this.categories.find(item => item.value === value)?.label || value;
    },

    levelLabel(level) {
        return LEVELS[level]?.label || level;
    },

    levelBadgeClass(level) {
        return LEVELS[level]?.badge || 'bg-secondary-lt';
    },

    formatDetail(detail) {
        try {
            return JSON.stringify(JSON.parse(detail), null, 2);
        } catch (e) {
            return detail;
        }
    },

    formatDateTime(value) {
        if (!value) return '-';
        const date = new Date(String(value).replace(' ', 'T'));
        if (Number.isNaN(date.getTime())) return value;
        return date.toLocaleString('zh-CN', { hour12: false });
    }
}).mount('#system-log-app');
