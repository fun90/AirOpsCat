import ApexCharts from '/static/js/apexcharts.js';

function getCurrentThemeMode() {
    return (
        document.documentElement.getAttribute('data-bs-theme') ||
        window.localStorage.getItem('tabler-theme') ||
        'light'
    );
}

function getThemeOptions(mode) {
    const isDark = mode === 'dark';
    const axisLabelColor = isDark ? '#d0d4db' : '#7e7e8d';
    const gridColor = isDark ? '#4b5563' : '#dce1e7';

    return {
        theme: { mode },
        tooltip: { theme: mode },
        xaxis: { labels: { style: { colors: axisLabelColor } } },
        yaxis: { labels: { style: { colors: axisLabelColor } } },
        legend: { labels: { colors: axisLabelColor } },
        grid: { borderColor: gridColor }
    };
}

const nodeOnlineAccountOverviewApp = {
    selectedDays: '30',
    loading: false,
    summary: null,
    chartData: null,
    chart: null,
    themeObserver: null,

    mounted() {
        this.refreshData();
        this.observeTheme();
        window.addEventListener('beforeunload', () => this.cleanup());
    },

    async refreshData() {
        this.loading = true;
        try {
            await Promise.all([this.fetchSummary(), this.fetchCharts()]);
            this.$nextTick(() => {
                if (this.summary?.dataAvailable) {
                    this.renderChart();
                } else {
                    this.destroyChart();
                }
            });
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '加载所有节点在线趋势失败', 'danger');
        } finally {
            this.loading = false;
        }
    },

    async fetchSummary() {
        const response = await fetch(`/api/admin/node-online-account-stats/overview/summary?days=${this.selectedDays}`);
        if (!response.ok) {
            throw new Error('加载所有节点在线趋势摘要失败');
        }
        this.summary = await response.json();
    },

    async fetchCharts() {
        const response = await fetch(`/api/admin/node-online-account-stats/overview/charts?days=${this.selectedDays}`);
        if (!response.ok) {
            throw new Error('加载所有节点在线趋势图表失败');
        }
        this.chartData = await response.json();
    },

    async changeDays() {
        try {
            await this.refreshData();
        } catch (error) {
            console.error(error);
        }
    },

    renderChart() {
        const points = Array.isArray(this.chartData?.points) ? this.chartData.points : [];
        if (points.length === 0) {
            this.destroyChart();
            return;
        }

        const categories = points.map(point => this.formatAxisDate(point.statDate));
        const uniqueData = points.map(point => Number(point.totalUniqueOnlineAccountCount || 0));
        const peakData = points.map(point => Number(point.totalPeakOnlineAccountCount || 0));
        const latestData = points.map(point => Number(point.totalLatestOnlineAccountCount || 0));

        this.renderLineChart('#node-online-account-overview-chart', categories, [
            { name: '每日承载账户', data: uniqueData },
            { name: '峰值承载账户', data: peakData },
            { name: '最近采样承载', data: latestData }
        ]);
    },

    renderLineChart(selector, categories, series) {
        const element = document.querySelector(selector);
        if (!element) {
            return;
        }

        this.destroyChart();
        const themeOptions = getThemeOptions(getCurrentThemeMode());
        const options = {
            chart: {
                type: 'area',
                height: 360,
                toolbar: { show: false },
                background: 'transparent'
            },
            theme: themeOptions.theme,
            series,
            colors: ['#206bc4', '#d63939', '#2fb344'],
            xaxis: {
                categories,
                axisBorder: { show: false },
                axisTicks: { show: false },
                labels: {
                    style: {
                        colors: themeOptions.xaxis.labels.style.colors
                    }
                }
            },
            yaxis: {
                min: 0,
                forceNiceScale: true,
                labels: {
                    formatter: value => Number(value || 0).toFixed(0),
                    style: {
                        colors: themeOptions.yaxis.labels.style.colors
                    }
                }
            },
            legend: {
                position: 'top',
                horizontalAlign: 'left',
                labels: {
                    colors: themeOptions.legend.labels.colors
                }
            },
            grid: {
                borderColor: themeOptions.grid.borderColor,
                strokeDashArray: 4
            },
            tooltip: {
                theme: themeOptions.tooltip.theme,
                shared: true,
                intersect: false,
                x: {
                    formatter: (_, context) => categories[context.dataPointIndex] || ''
                },
                y: {
                    formatter: value => `${Number(value || 0).toFixed(0)} 个节点承载账户`
                }
            },
            dataLabels: { enabled: false },
            stroke: {
                curve: 'smooth',
                width: 2
            },
            fill: {
                type: 'gradient',
                gradient: {
                    shadeIntensity: 1,
                    opacityFrom: 0.35,
                    opacityTo: 0.08,
                    stops: [0, 90, 100]
                }
            }
        };

        this.chart = new ApexCharts(element, options);
        this.chart.render();
    },

    observeTheme() {
        this.themeObserver = new MutationObserver(() => {
            if (this.summary?.dataAvailable) {
                this.renderChart();
            }
        });
        this.themeObserver.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-bs-theme']
        });
    },

    cleanup() {
        if (this.themeObserver) {
            this.themeObserver.disconnect();
            this.themeObserver = null;
        }
        this.destroyChart();
    },

    destroyChart() {
        if (this.chart) {
            this.chart.destroy();
            this.chart = null;
        }
    },

    singleNodeTrendUrl(nodeId) {
        return `/console/vpn/node-online-account-stats?nodeId=${nodeId}`;
    },

    formatAxisDate(value) {
        if (!value) {
            return '-';
        }
        const date = new Date(`${value}T00:00:00`);
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        return `${month}-${day}`;
    },

    formatDateTime(value) {
        if (!value) {
            return '-';
        }
        const date = new Date(value);
        return date.toLocaleString('zh-CN', {
            hour12: false
        });
    },

    formatCount(value) {
        return Number(value || 0).toFixed(0);
    },

    formatAverage(value) {
        return Number(value || 0).toFixed(1);
    }
};

PetiteVue.createApp(nodeOnlineAccountOverviewApp).mount('#app');
