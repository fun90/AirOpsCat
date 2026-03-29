import { Modal } from '/static/tabler/js/tabler.esm.min.js';
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

const serverMonitorApp = {
    serverId: null,
    serverIdError: false,
    selectedHours: '24',
    loading: false,
    clearing: false,
    summary: null,
    chartData: null,
    clearModal: null,
    trafficCalibrationModal: null,
    cpuChart: null,
    memoryChart: null,
    networkRateChart: null,
    networkTotalChart: null,
    refreshTimer: null,
    themeObserver: null,
    validationErrors: {},
    trafficCalibration: {
        uploadGb: '',
        downloadGb: ''
    },

    mounted() {
        const params = new URLSearchParams(window.location.search);
        const serverId = params.get('serverId');
        if (!serverId || Number.isNaN(Number(serverId))) {
            this.serverIdError = true;
            return;
        }

        this.serverId = Number(serverId);
        this.clearModal = new Modal(document.getElementById('server-monitor-clearModal'));
        this.trafficCalibrationModal = new Modal(document.getElementById('server-monitor-trafficCalibrationModal'));
        this.refreshData();
        this.observeTheme();
        window.addEventListener('beforeunload', () => this.cleanup());
    },

    async refreshData() {
        if (!this.serverId) {
            return;
        }

        this.loading = true;
        try {
            await Promise.all([this.fetchSummary(), this.fetchCharts()]);
            this.$nextTick(() => {
                if (this.summary?.dataAvailable) {
                    this.renderCharts();
                } else {
                    this.destroyCharts();
                }
            });
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '加载服务器监控失败', 'danger');
        } finally {
            this.loading = false;
        }
    },

    async fetchSummary() {
        const response = await fetch(`/api/admin/server-monitors/${this.serverId}/summary`);
        if (!response.ok) {
            let errorMessage = '加载监控摘要失败';
            try {
                const errorData = await response.json();
                errorMessage = errorData.message || errorMessage;
            } catch (_) {
            }
            throw new Error(errorMessage);
        }
        this.summary = await response.json();
        this.startAutoRefresh();
    },

    async fetchCharts() {
        if (!this.serverId) {
            return;
        }

        try {
            const response = await fetch(`/api/admin/server-monitors/${this.serverId}/charts?hours=${this.selectedHours}`);
            if (!response.ok) {
                let errorMessage = '加载监控图表失败';
                try {
                    const errorData = await response.json();
                    errorMessage = errorData.message || errorMessage;
                } catch (_) {
                }
                throw new Error(errorMessage);
            }
            this.chartData = await response.json();
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '加载监控图表失败', 'danger');
            throw error;
        }
    },

    async changeHours() {
        try {
            await this.fetchCharts();
            this.$nextTick(() => {
                if (this.summary?.dataAvailable) {
                    this.renderCharts();
                } else {
                    this.destroyCharts();
                }
            });
        } catch (error) {
            console.error(error);
        }
    },

    openClearModal() {
        if (this.clearModal) {
            this.clearModal.show();
        }
    },

    openTrafficCalibrationModal() {
        if (!this.summary) {
            return;
        }
        this.validationErrors = {};
        this.trafficCalibration = {
            uploadGb: this.bytesToGbValue(this.summary.networkTxBytes),
            downloadGb: this.bytesToGbValue(this.summary.networkRxBytes)
        };
        if (this.trafficCalibrationModal) {
            this.trafficCalibrationModal.show();
        }
    },

    async clearRecords() {
        if (!this.serverId) {
            return;
        }

        this.clearing = true;
        try {
            const response = await fetch(`/api/admin/server-monitors/${this.serverId}/records`, {
                method: 'DELETE'
            });
            if (!response.ok) {
                throw new Error('清空监控数据失败');
            }

            this.clearModal.hide();
            this.summary = {
                ...this.summary,
                dataAvailable: false,
                sampleTime: null,
                cpuUsage: 0,
                cpuCores: 0,
                memoryUsage: 0,
                memoryUsedBytes: 0,
                memoryTotalBytes: 0,
                networkRxBytes: 0,
                networkTxBytes: 0,
                networkRxRateBytes: 0,
                networkTxRateBytes: 0
            };
            this.chartData = {
                ...(this.chartData || {}),
                points: []
            };
            this.destroyCharts();
            ToastUtils.show('Success', '监控数据已清空', 'success');
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '清空监控数据失败', 'danger');
        } finally {
            this.clearing = false;
        }
    },

    validateTrafficCalibrationForm() {
        let isValid = true;
        this.validationErrors = {};

        const uploadGb = parseFloat(this.trafficCalibration.uploadGb);
        if (this.trafficCalibration.uploadGb === '' || Number.isNaN(uploadGb) || uploadGb < 0) {
            this.validationErrors.trafficUploadGb = '请输入有效的累计上传流量';
            isValid = false;
        }

        const downloadGb = parseFloat(this.trafficCalibration.downloadGb);
        if (this.trafficCalibration.downloadGb === '' || Number.isNaN(downloadGb) || downloadGb < 0) {
            this.validationErrors.trafficDownloadGb = '请输入有效的累计下载流量';
            isValid = false;
        }

        return isValid;
    },

    async saveTrafficCalibration() {
        if (!this.validateTrafficCalibrationForm()) {
            return;
        }

        try {
            const response = await fetch(`/api/admin/server-monitors/${this.serverId}/traffic-calibration`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    uploadGb: this.trafficCalibration.uploadGb,
                    downloadGb: this.trafficCalibration.downloadGb
                })
            });

            if (!response.ok) {
                let errorMessage = '流量校准失败';
                try {
                    const errorData = await response.json();
                    errorMessage = errorData.message || errorMessage;
                } catch (_) {
                }
                throw new Error(errorMessage);
            }

            this.summary = await response.json();
            this.startAutoRefresh();
            await this.fetchCharts();
            this.$nextTick(() => {
                if (this.summary?.dataAvailable) {
                    this.renderCharts();
                } else {
                    this.destroyCharts();
                }
            });
            this.trafficCalibrationModal.hide();
            ToastUtils.show('Success', '累计流量校准成功', 'success');
        } catch (error) {
            console.error(error);
            ToastUtils.show('Error', error.message || '流量校准失败', 'danger');
        }
    },

    renderCharts() {
        const points = Array.isArray(this.chartData?.points) ? this.chartData.points : [];
        if (points.length === 0) {
            this.destroyCharts();
            return;
        }

        const categories = points.map(point => this.formatAxisTime(point.sampleTime));
        const cpuData = points.map(point => Number(point.cpuUsage || 0));
        const memoryData = points.map(point => Number(point.memoryUsage || 0));
        const uploadRateData = points.map(point => Number(point.networkTxRateBytes || 0));
        const downloadRateData = points.map(point => Number(point.networkRxRateBytes || 0));
        const uploadTotalData = points.map(point => Number(point.networkTxBytes || 0));
        const downloadTotalData = points.map(point => Number(point.networkRxBytes || 0));

        this.renderLineChart('cpuChart', '#cpu-chart', categories, [
            { name: 'CPU 使用率', data: cpuData }
        ], {
            colors: ['#206bc4'],
            yaxis: {
                min: 0,
                max: 100,
                labels: { formatter: value => `${value.toFixed(0)}%` }
            }
        });

        this.renderLineChart('memoryChart', '#memory-chart', categories, [
            { name: '内存使用率', data: memoryData }
        ], {
            colors: ['#4299e1'],
            yaxis: {
                min: 0,
                max: 100,
                labels: { formatter: value => `${value.toFixed(0)}%` }
            }
        });

        this.renderLineChart('networkRateChart', '#network-rate-chart', categories, [
            { name: '上传速率', data: uploadRateData },
            { name: '下载速率', data: downloadRateData }
        ], {
            colors: ['#2fb344', '#f59f00'],
            yaxis: {
                labels: { formatter: value => this.formatBytesPerSecond(value) }
            }
        });

        this.renderLineChart('networkTotalChart', '#network-total-chart', categories, [
            { name: '累计上传', data: uploadTotalData },
            { name: '累计下载', data: downloadTotalData }
        ], {
            colors: ['#12b886', '#fd7e14'],
            yaxis: {
                labels: { formatter: value => this.formatBytes(value) }
            }
        });
    },

    renderLineChart(chartKey, selector, categories, series, overrides = {}) {
        const element = document.querySelector(selector);
        if (!element) {
            return;
        }

        if (this[chartKey]) {
            this[chartKey].destroy();
        }

        const themeOptions = getThemeOptions(getCurrentThemeMode());
        const options = {
            chart: {
                type: 'area',
                height: 320,
                toolbar: { show: false },
                background: 'transparent'
            },
            theme: themeOptions.theme,
            series,
            xaxis: {
                categories,
                axisBorder: {
                    show: false
                },
                axisTicks: {
                    show: false
                },
                labels: {
                    show: false,
                    style: {
                        colors: themeOptions.xaxis.labels.style.colors
                    }
                }
            },
            yaxis: {
                labels: {
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
                }
            },
            dataLabels: {
                enabled: false
            },
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
            },
            ...overrides
        };

        if (Array.isArray(options.yaxis)) {
            options.yaxis = options.yaxis.map(axis => ({
                ...axis,
                labels: {
                    ...(axis.labels || {}),
                    style: {
                        colors: themeOptions.yaxis.labels.style.colors
                    }
                }
            }));
        }

        this[chartKey] = new ApexCharts(element, options);
        this[chartKey].render();
    },

    observeTheme() {
        this.themeObserver = new MutationObserver(() => {
            this.renderCharts();
        });

        this.themeObserver.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-bs-theme']
        });
    },

    startAutoRefresh() {
        if (this.refreshTimer) {
            clearInterval(this.refreshTimer);
            this.refreshTimer = null;
        }

        const intervalSeconds = Number(this.summary?.monitorIntervalSeconds || 60);
        const intervalMs = Math.max(intervalSeconds, 5) * 1000;
        this.refreshTimer = window.setInterval(() => {
            this.refreshData().catch(error => {
                console.error(error);
            });
        }, intervalMs);
    },

    cleanup() {
        if (this.refreshTimer) {
            clearInterval(this.refreshTimer);
            this.refreshTimer = null;
        }
        if (this.themeObserver) {
            this.themeObserver.disconnect();
            this.themeObserver = null;
        }
        this.destroyCharts();
    },

    destroyCharts() {
        ['cpuChart', 'memoryChart', 'networkRateChart', 'networkTotalChart'].forEach(chartKey => {
            if (this[chartKey]) {
                this[chartKey].destroy();
                this[chartKey] = null;
            }
        });
    },

    formatAxisTime(value) {
        if (!value) {
            return '-';
        }
        const date = new Date(value);
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');
        return `${month}-${day} ${hours}:${minutes}`;
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

    formatPercent(value) {
        const numericValue = Number(value || 0);
        return `${numericValue.toFixed(2)}%`;
    },

    bytesToGbValue(bytes) {
        const value = Number(bytes || 0) / (1024 * 1024 * 1024);
        return Number.isFinite(value) ? value.toFixed(2) : '0.00';
    },

    formatBytes(value) {
        const bytes = Number(value || 0);
        if (bytes <= 0) {
            return '0 B';
        }
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        let unitIndex = 0;
        let size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        const decimals = unitIndex >= 3 ? 2 : (size >= 10 || unitIndex === 0 ? 0 : 2);
        return `${size.toFixed(decimals)} ${units[unitIndex]}`;
    },

    formatBytesPerSecond(value) {
        return `${this.formatBytes(value)}/s`;
    }
};

PetiteVue.createApp(serverMonitorApp).mount('#app');
