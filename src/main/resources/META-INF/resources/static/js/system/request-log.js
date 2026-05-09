import ApexCharts from '/static/js/apexcharts.js';

function getCurrentThemeMode() {
    return (
        document.documentElement.getAttribute('data-bs-theme') ||
        window.localStorage.getItem('tabler-theme') ||
        'light'
    );
}

function getApexThemeOptions(mode) {
    const isDark = mode === 'dark';
    const axisLabelColor = isDark ? '#d0d4db' : '#7e7e8d';
    const gridColor = isDark ? '#d0d4db' : '#a8afaf';

    return {
        theme: { mode },
        tooltip: { theme: mode },
        xaxis: { labels: { style: { colors: axisLabelColor } } },
        yaxis: { labels: { style: { colors: axisLabelColor } } },
        legend: { labels: { colors: axisLabelColor } },
        grid: { borderColor: gridColor }
    };
}

function shortLabel(value, maxLength) {
    if (!value) return '-';
    return value.length > maxLength ? `${value.slice(0, maxLength - 1)}...` : value;
}

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
    ipChart: null,

    initialize() {
        this.refresh();
        this.initThemeObserver();
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
        this.pathChart = this.renderPathDateChart(this.pathChart, '#request-path-chart', stats.pathStats || []);
        this.ipChart = this.renderBarChart(
            this.ipChart,
            '#request-ip-chart',
            stats.clientIpStats || [],
            '请求数'
        );
    },

    renderPathDateChart(instance, selector, items) {
        const categories = [...new Set(items.flatMap(item => (item.dateStats || []).map(point => point.label)))].sort();
        const series = items.map(item => {
            const pointsByDate = new Map((item.dateStats || []).map(point => [point.label, point.count || 0]));
            return {
                name: shortLabel(item.requestPath || '-', 48),
                data: categories.map(date => pointsByDate.get(date) || 0)
            };
        });
        const fullLabels = items.map(item => item.requestPath || '-');
        const themeOptions = getApexThemeOptions(getCurrentThemeMode());
        const options = {
            chart: { type: 'line', height: 320, toolbar: { show: false }, background: 'transparent' },
            theme: themeOptions.theme,
            series,
            colors: ['#206bc4', '#2fb344', '#f59f00', '#d6336c', '#4299e1', '#ae3ec9', '#0ca678', '#f76707'],
            xaxis: {
                categories,
                labels: {
                    rotate: -30,
                    style: { colors: themeOptions.xaxis.labels.style.colors }
                }
            },
            yaxis: {
                min: 0,
                forceNiceScale: true,
                labels: {
                    style: { colors: themeOptions.yaxis.labels.style.colors },
                    formatter: value => Math.round(value)
                }
            },
            stroke: { curve: 'smooth', width: 3 },
            markers: { size: 3 },
            dataLabels: { enabled: false },
            legend: {
                position: 'top',
                horizontalAlign: 'left',
                labels: { colors: themeOptions.legend.labels.colors },
                formatter: (_seriesName, opts) => fullLabels[opts.seriesIndex] || _seriesName
            },
            grid: {
                borderColor: themeOptions.grid.borderColor,
                strokeDashArray: 5
            },
            tooltip: {
                theme: themeOptions.tooltip.theme,
                shared: true,
                intersect: false,
                custom: ({ series, dataPointIndex }) => {
                    const date = categories[dataPointIndex] || '-';
                    const rows = series.map((values, index) => {
                        const label = fullLabels[index] || '-';
                        return `<div class="d-flex align-items-center gap-2 mt-1">
                            <span class="badge badge-empty" style="background-color: ${options.colors[index % options.colors.length]}"></span>
                            <span class="text-wrap" style="max-width: 420px;">${this.escapeHtml(label)}: ${values[dataPointIndex] || 0}</span>
                        </div>`;
                    }).join('');
                    return `<div class="px-3 py-2">
                        <div class="fw-medium">${this.escapeHtml(date)}</div>
                        ${rows}
                    </div>`;
                }
            },
            noData: { text: '暂无数据' }
        };
        return this.renderChart(instance, selector, options);
    },

    renderBarChart(instance, selector, items, seriesName) {
        const labels = items.map(item => item.label || '-');
        const displayLabels = labels.map(label => shortLabel(label, 18));
        const values = items.map(item => item.count || 0);
        const themeOptions = getApexThemeOptions(getCurrentThemeMode());
        const options = {
            chart: { type: 'bar', height: 320, toolbar: { show: false }, background: 'transparent' },
            theme: themeOptions.theme,
            series: [{ name: seriesName, data: values }],
            colors: ['#2fb344'],
            plotOptions: {
                bar: {
                    horizontal: false,
                    borderRadius: 3,
                    columnWidth: '48%'
                }
            },
            xaxis: {
                categories: displayLabels,
                labels: {
                    trim: true,
                    style: { colors: themeOptions.xaxis.labels.style.colors }
                }
            },
            yaxis: {
                min: 0,
                forceNiceScale: true,
                labels: {
                    style: { colors: themeOptions.yaxis.labels.style.colors },
                    formatter: value => Math.round(value)
                }
            },
            grid: {
                borderColor: themeOptions.grid.borderColor,
                strokeDashArray: 5
            },
            dataLabels: { enabled: false },
            tooltip: {
                theme: themeOptions.tooltip.theme,
                custom: ({ dataPointIndex }) => {
                    const label = labels[dataPointIndex] || '-';
                    const value = values[dataPointIndex] || 0;
                    return `<div class="px-3 py-2">
                        <div class="fw-medium text-wrap" style="max-width: 420px;">${this.escapeHtml(label)}</div>
                        <div class="text-secondary small mt-1">${seriesName}: ${value}</div>
                    </div>`;
                }
            },
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

    initThemeObserver() {
        const applyTheme = () => {
            const themeOptions = getApexThemeOptions(getCurrentThemeMode());
            [this.pathChart, this.ipChart].forEach(chart => {
                if (!chart) return;
                const chartConfig = chart.w && chart.w.config;
                const yaxisConfig = Array.isArray(chartConfig.yaxis) ? chartConfig.yaxis[0] : chartConfig.yaxis;
                const xaxisLabels = chartConfig.xaxis && chartConfig.xaxis.labels ? chartConfig.xaxis.labels : {};
                const yaxisLabels = yaxisConfig && yaxisConfig.labels ? yaxisConfig.labels : {};

                chart.updateOptions({
                    theme: themeOptions.theme,
                    tooltip: themeOptions.tooltip,
                    xaxis: {
                        labels: {
                            ...xaxisLabels,
                            style: { colors: themeOptions.xaxis.labels.style.colors }
                        }
                    },
                    yaxis: {
                        labels: {
                            ...yaxisLabels,
                            style: { colors: themeOptions.yaxis.labels.style.colors }
                        }
                    },
                    legend: themeOptions.legend,
                    grid: themeOptions.grid
                }, false, true);
            });
        };

        const observer = new MutationObserver(applyTheme);
        observer.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-bs-theme']
        });
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
    },

    escapeHtml(value) {
        return String(value ?? '').replace(/[&<>"']/g, char => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        }[char]));
    }
}).mount('#request-log-app');
