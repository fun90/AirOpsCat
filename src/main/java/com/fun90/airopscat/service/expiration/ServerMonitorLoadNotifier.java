package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.ServerMonitorStatsService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.repository.ServerRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@ApplicationScoped
public class ServerMonitorLoadNotifier implements MonitorNotifier {

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final String ALERT_TYPE = "server-monitor-load";
    private static final String RESOURCE_TYPE = "server";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RECOVERED = "RECOVERED";
    private static final String SEVERITY_WARNING = "WARNING";
    private static final String SUB_CPU = "cpu";
    private static final String SUB_MEMORY = "memory";
    private static final String SUB_TRAFFIC = "traffic";

    @Inject
    ServerRepository serverRepository;

    @Inject
    ServerMonitorStatsService serverMonitorStatsService;

    @Inject
    @Named("monitorTaskExecutor")
    ExecutorService monitorTaskExecutor;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    AlertStateRepository alertStateRepository;

    @Inject
    BarkService barkService;

    @Override
    public String getType() {
        return "server-monitor-load";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 服务器监控提醒";
    }

    @Override
    @Transactional
    public List<String> findItems(LocalDate today) {
        LocalDateTime now = LocalDateTime.now();
        List<Server> servers = serverRepository.findMonitorableServers(today);
        if (servers.isEmpty()) return List.of();

        MonitorAlertThresholds thresholds = loadThresholds();
        List<ServerMetricResult> results = collectMetrics(servers, now, thresholds);

        for (ServerMetricResult result : results) {
            processMetric(result.server(), SUB_CPU, result.cpuHigh(),
                    buildUsageAlert(result.server(), "CPU", normalizePercentThreshold(thresholds.cpuThreshold()), thresholds.continuousMinutes()),
                    now);
            processMetric(result.server(), SUB_MEMORY, result.memoryHigh(),
                    buildUsageAlert(result.server(), "内存", normalizePercentThreshold(thresholds.memoryThreshold()), thresholds.continuousMinutes()),
                    now);
            processMetric(result.server(), SUB_TRAFFIC, result.trafficHigh(),
                    buildTrafficAlert(result.server(), result.trafficRatio(), result.totalTrafficBytes()),
                    now);
        }

        return List.of();
    }

    @Override
    public String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        return String.join("\n", items);
    }

    private List<ServerMetricResult> collectMetrics(List<Server> servers, LocalDateTime now, MonitorAlertThresholds thresholds) {
        int maxParallel = Math.max(systemConfigService.getIntValue("airopscat.server.monitor.max-parallel-servers", 10), 1);
        int batchCount = servers.isEmpty() ? 0 : (int) Math.ceil((double) servers.size() / maxParallel);
        List<ServerMetricResult> allResults = new ArrayList<>();

        for (int startIndex = 0; startIndex < servers.size(); startIndex += maxParallel) {
            int endIndex = Math.min(startIndex + maxParallel, servers.size());
            List<Server> batch = servers.subList(startIndex, endIndex);
            int currentBatch = (startIndex / maxParallel) + 1;
            log.info("服务器监控提醒批次开始，总服务器数: {}, 当前批次: {}/{}, 批大小: {}",
                    servers.size(), currentBatch, batchCount, batch.size());

            List<CompletableFuture<ServerMetricResult>> futures = batch.stream()
                    .map(server -> CompletableFuture.supplyAsync(
                            () -> checkServerMetrics(server, now, thresholds), monitorTaskExecutor))
                    .toList();

            for (CompletableFuture<ServerMetricResult> future : futures) {
                ServerMetricResult result = future.join();
                if (result != null) allResults.add(result);
            }
        }
        return allResults;
    }

    private ServerMetricResult checkServerMetrics(Server server, LocalDateTime now, MonitorAlertThresholds thresholds) {
        try {
            if (server.getId() == null) return null;
            double cpuThresholdPercent = normalizePercentThreshold(thresholds.cpuThreshold());
            boolean cpuHigh = serverMonitorStatsService.isCpuUsageHighForDuration(
                    server.getId(), now, cpuThresholdPercent, thresholds.continuousMinutes());

            double memoryThresholdPercent = normalizePercentThreshold(thresholds.memoryThreshold());
            boolean memoryHigh = serverMonitorStatsService.isMemoryUsageHighForDuration(
                    server.getId(), now, memoryThresholdPercent, thresholds.continuousMinutes());

            long totalTrafficBytes = serverMonitorStatsService.getCurrentPeriodTotalTrafficBytes(server, now);
            long limitBytes = server.getBandwidth() == null ? 0L : server.getBandwidth().longValue() * BYTES_PER_GB;
            double trafficRatio = limitBytes <= 0 ? 0D : totalTrafficBytes / (double) limitBytes;
            boolean trafficHigh = trafficRatio >= normalizeThreshold(thresholds.trafficThreshold());

            return new ServerMetricResult(server, cpuHigh, memoryHigh, trafficHigh, totalTrafficBytes, trafficRatio);
        } catch (Exception e) {
            log.error("检查服务器监控指标失败，serverId={}, error={}", server.getId(), e.getMessage(), e);
            return null;
        }
    }

    private void processMetric(Server server, String subType, boolean isHigh, String summary, LocalDateTime now) {
        String fp = fingerprint(server, subType);
        try {
            AlertState state = alertStateRepository
                    .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, server.getId(), fp)
                    .orElse(null);

            if (isHigh) {
                if (state == null) {
                    state = newAlertState(server, fp, now);
                    alertStateRepository.persist(state);
                } else if (!STATUS_ACTIVE.equals(state.getStatus())) {
                    state.setStatus(STATUS_ACTIVE);
                    state.setFirstTriggeredTime(now);
                    state.setRecoveredTime(null);
                }
                state.setLastTriggeredTime(now);
                state.setTriggerCount((state.getTriggerCount() == null ? 0 : state.getTriggerCount()) + 1);
                state.setSummary(summary);

                if (shouldNotify(state, now)) {
                    barkService.sendWarningNotification(getTitle(), summary);
                    state.setLastNotifiedTime(now);
                }
            } else if (state != null && STATUS_ACTIVE.equals(state.getStatus())) {
                state.setStatus(STATUS_RECOVERED);
                state.setRecoveredTime(now);
                state.setLastTriggeredTime(now);
            }
        } catch (Exception e) {
            log.warn("处理服务器监控告警状态失败，serverId={}, subType={}: {}", server.getId(), subType, e.getMessage());
        }
    }

    private boolean shouldNotify(AlertState state, LocalDateTime now) {
        int minutes = Math.max(0, systemConfigService.getIntValue(
                "airopscat.server.monitor.alert.min-interval-minutes", 60));
        return state.getLastNotifiedTime() == null
                || !state.getLastNotifiedTime().plusMinutes(minutes).isAfter(now);
    }

    private AlertState newAlertState(Server server, String fingerprint, LocalDateTime now) {
        AlertState state = new AlertState();
        state.setAlertType(ALERT_TYPE);
        state.setResourceType(RESOURCE_TYPE);
        state.setResourceId(server.getId());
        state.setResourceKey(server.getIp() != null ? server.getIp() : String.valueOf(server.getId()));
        state.setFingerprint(fingerprint);
        state.setStatus(STATUS_ACTIVE);
        state.setSeverity(SEVERITY_WARNING);
        state.setFirstTriggeredTime(now);
        state.setTriggerCount(0);
        return state;
    }

    private String fingerprint(Server server, String subType) {
        return subType + ":" + server.getId();
    }

    private MonitorAlertThresholds loadThresholds() {
        return new MonitorAlertThresholds(
                systemConfigService.getDoubleValue("airopscat.server.monitor.alert.cpu-threshold", 0.9D),
                systemConfigService.getDoubleValue("airopscat.server.monitor.alert.memory-threshold", 0.95D),
                systemConfigService.getDoubleValue("airopscat.server.monitor.alert.traffic-threshold", 0.85D),
                Math.max(1, systemConfigService.getIntValue("airopscat.server.monitor.alert.continuous-minutes", 30))
        );
    }

    private String buildUsageAlert(Server server, String metricName, double threshold, int durationMinutes) {
        return buildServerLabel(server) + " " + metricName + "使用率连续"
                + durationMinutes + "分钟达到 " + formatPercentValue(threshold);
    }

    private String buildTrafficAlert(Server server, double usageRatio, long totalTrafficBytes) {
        long limitBytes = server.getBandwidth() == null ? 0L : server.getBandwidth().longValue() * BYTES_PER_GB;
        return buildServerLabel(server) + " 累计流量达到 " + formatPercentValue(usageRatio * 100D)
                + " (" + formatBytes(totalTrafficBytes) + " / " + formatBytes(limitBytes) + ")";
    }

    private String buildServerLabel(Server server) {
        String name = server.getName();
        String ip = server.getIp();
        return (name == null || name.isBlank()) ? ip : name + " (" + ip + ")";
    }

    private double normalizeThreshold(double value) {
        if (value <= 0) return 1.0;
        if (value > 1) return value / 100.0;
        return value;
    }

    private double normalizePercentThreshold(double value) {
        return normalizeThreshold(value) * 100D;
    }

    private String formatPercentValue(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.2f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.2f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return String.format(Locale.ROOT, "%.2f GB", gb);
        return String.format(Locale.ROOT, "%.2f TB", gb / 1024.0);
    }

    private record ServerMetricResult(Server server, boolean cpuHigh, boolean memoryHigh, boolean trafficHigh,
                                      long totalTrafficBytes, double trafficRatio) {}

    private record MonitorAlertThresholds(double cpuThreshold, double memoryThreshold,
                                          double trafficThreshold, int continuousMinutes) {}
}
