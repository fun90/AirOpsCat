import ApexCharts from '/static/js/apexcharts.js';

const requestLogApp = PetiteVue.createApp({
    loading: true,
    logs: [],
    currentPage: 1,
    pageSize: 20,
    totalPages: 0,
    totalRecords: 0,
    filters: {
        startTime: '',
        endTime: '',
        requestPath: '',
        clientIp: ''
    },
    pathChart: null,
    trendChart: null,
    ipChart: null,

    initialize() {
        this.refresh();
    },

    async refresh() {
        await Promise.all([this.loadLogs(), this.loadStats()]);
    },

    async search() {
        this.currentPage = 1;
        await this.refresh();
    },

    async resetFilters() {
        this.filters = { startTime: '', endTime: '', requestPath: '', clientIp: '' };
        this.currentPage = 1;
        await this.refresh();
    },

    async loadLogs() {
        this.loading = true;
        try {
            const response = await fetch(`/api/admin/system-request-logs?${this.buildParams(true)}`);
            const payload = await response.json();
            if (!response.ok || !payload.success) {
                throw new Error(payload.message || '加载请求日志失败');
            }
            const page = payload.data || {};
            this.logs = page.records || [];
            this.totalRecords = page.total || 0;
            this.totalPages = page.pages || 0;
            this.currentPage = page.current || this.currentPage;
            this.pageSize = page.size || this.pageSize;
        } catch (error) {
            ToastUtils.show('错误', error.message || '加载请求日志失败', 'danger');
        } finally {
            this.loading = false;
        }
    },

    async loadStats() {
        try {
            const response = await fetch(`/api/admin/system-request-logs/stats?${this.buildParams(false)}`);
            const payload = await response.json();
            if (!response.ok || !payload.success) {
                throw new Error(payload.message || '加载请求日志统计失败');
            }
            this.renderCharts(payload.data || {});
        } catch (error) {
            ToastUtils.show('错误', error.message || '加载请求日志统计失败', 'danger');
        }
    },

    buildParams(includePage) {
        const params = new URLSearchParams();
        if (includePage) {
            params.set('page', this.currentPage);
            params.set('size', this.pageSize);
        }
        Object.entries(this.filters).forEach(([key, value]) => {
            if (value) params.set(key, value);
        });
        return params;
    },

    renderCharts(stats) {
        this.pathChart = this.renderBarChart(
            this.pathChart,
            '#request-path-chart',
            stats.pathStats || [],
            '请求数',
            true
        );
        this.trendChart = this.renderLineChart(
            this.trendChart,
            '#request-trend-chart',
            stats.trendStats || []
        );
        this.ipChart = this.renderBarChart(
            this.ipChart,
            '#request-ip-chart',
            stats.clientIpStats || [],
            '请求数',
            false
        );
    },

    renderBarChart(instance, selector, items, seriesName, horizontal) {
        const labels = items.map(item => item.label || '-');
        const values = items.map(item => item.count || 0);
        const options = {
            chart: { type: 'bar', height: 280, toolbar: { show: false } },
            series: [{ name: seriesName, data: values }],
            plotOptions: { bar: { horizontal, borderRadius: 3 } },
            xaxis: { categories: labels, labels: { trim: true } },
            dataLabels: { enabled: false },
            noData: { text: '暂无数据' }
        };
        return this.renderChart(instance, selector, options);
    },

    renderLineChart(instance, selector, items) {
        const labels = items.map(item => item.label || '-');
        const values = items.map(item => item.count || 0);
        const options = {
            chart: { type: 'line', height: 280, toolbar: { show: false } },
            series: [{ name: '请求数', data: values }],
            xaxis: { categories: labels, labels: { rotate: -30 } },
            stroke: { curve: 'smooth', width: 3 },
            markers: { size: 3 },
            dataLabels: { enabled: false },
            noData: { text: '暂无数据' }
        };
        return this.renderChart(instance, selector, options);
    },

    renderChart(instance, selector, options) {
        const element = document.querySelector(selector);
        if (!element) return instance;
        if (instance) {
            instance.updateOptions(options, true, true);
            return instance;
        }
        const chart = new ApexCharts(element, options);
        chart.render();
        return chart;
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

    formatDateTime(value) {
        if (!value) return '-';
        const date = new Date(String(value).replace(' ', 'T'));
        if (Number.isNaN(date.getTime())) return value;
        return date.toLocaleString('zh-CN', { hour12: false });
    },

    statusBadgeClass(statusCode) {
        if (statusCode >= 500) return 'bg-danger-lt';
        if (statusCode >= 400) return 'bg-warning-lt';
        if (statusCode >= 300) return 'bg-info-lt';
        return 'bg-success-lt';
    }
}).mount('#request-log-app');
