package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.ServerMonitorStatsService;
import com.fun90.airopscat.service.SystemConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Slf4j
@ApplicationScoped
public class ServerMonitorLoadNotifier implements MonitorNotifier {

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final String CPU_ALERT_KEY = "cpu";
    private static final String MEMORY_ALERT_KEY = "memory";
    private static final String TRAFFIC_ALERT_KEY = "traffic";

    private final Map<String, Boolean> activeAlerts = new ConcurrentHashMap<>();

    @Inject
    ServerRepository serverRepository;

    @Inject
    ServerMonitorStatsService serverMonitorStatsService;

    @Inject
    @Named("monitorTaskExecutor")
    ExecutorService monitorTaskExecutor;

    @Inject
    SystemConfigService systemConfigService;

    @Override
    public String getType() {
        return "server-monitor-load";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 服务器监控提醒";
    }

    @Override
    public List<String> findItems(LocalDate today) {
        LocalDateTime now = LocalDateTime.now();
        List<Server> servers = serverRepository.findMonitorableServers(today);
        if (servers.isEmpty()) {
            return List.of();
        }

        MonitorAlertThresholds thresholds = loadThresholds();
        List<String> items = new ArrayList<>();
        int maxParallelServers = getMaxParallelServers();
        int batchCount = calculateBatchCount(servers.size(), maxParallelServers);
        for (int startIndex = 0; startIndex < servers.size(); startIndex += maxParallelServers) {
            int endIndex = Math.min(startIndex + maxParallelServers, servers.size());
            List<Server> batch = servers.subList(startIndex, endIndex);
            int currentBatch = (startIndex / maxParallelServers) + 1;
            log.info("服务器监控提醒批次开始，总服务器数: {}, 当前批次: {}/{}, 批大小: {}",
                    servers.size(), currentBatch, batchCount, batch.size());

            List<CompletableFuture<List<String>>> futures = batch.stream()
                    .map(server -> CompletableFuture.supplyAsync(() -> checkServer(server, now, thresholds), monitorTaskExecutor))
                    .toList();

            for (CompletableFuture<List<String>> future : futures) {
                List<String> serverItems = future.join();
                if (serverItems != null && !serverItems.isEmpty()) {
                    items.addAll(serverItems);
                }
            }
        }
        return items;
    }

    private int getMaxParallelServers() {
        return Math.max(systemConfigService.getIntValue("airopscat.server.monitor.max-parallel-servers", 10), 1);
    }

    private int calculateBatchCount(int totalCount, int batchSize) {
        return totalCount == 0 ? 0 : (int) Math.ceil((double) totalCount / batchSize);
    }

    @Override
    public String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return String.join("\n", items);
    }

    private List<String> checkServer(Server server, LocalDateTime now, MonitorAlertThresholds thresholds) {
        List<String> items = new ArrayList<>();
        try {
            Long serverId = server.getId();
            if (serverId == null) {
                return items;
            }

            int continuousMinutes = thresholds.continuousMinutes();

            double cpuThresholdPercent = normalizePercentThreshold(thresholds.cpuThreshold());
            boolean cpuHigh = serverMonitorStatsService.isCpuUsageHighForDuration(
                    serverId, now, cpuThresholdPercent, continuousMinutes);
            updateAlertState(serverId, CPU_ALERT_KEY, cpuHigh);
            if (cpuHigh && activateAlert(serverId, CPU_ALERT_KEY)) {
                items.add(buildUsageAlert(server, "CPU", cpuThresholdPercent, continuousMinutes));
            }

            double memoryThresholdPercent = normalizePercentThreshold(thresholds.memoryThreshold());
            boolean memoryHigh = serverMonitorStatsService.isMemoryUsageHighForDuration(
                    serverId, now, memoryThresholdPercent, continuousMinutes);
            updateAlertState(serverId, MEMORY_ALERT_KEY, memoryHigh);
            if (memoryHigh && activateAlert(serverId, MEMORY_ALERT_KEY)) {
                items.add(buildUsageAlert(server, "内存", memoryThresholdPercent, continuousMinutes));
            }

            long totalTrafficBytes = serverMonitorStatsService.getCurrentPeriodTotalTrafficBytes(server, now);
            long limitBytes = server.getBandwidth() == null ? 0L : server.getBandwidth().longValue() * BYTES_PER_GB;
            double currentTrafficRatio = limitBytes <= 0 ? 0D : totalTrafficBytes / (double) limitBytes;
            boolean trafficHigh = currentTrafficRatio >= normalizeThreshold(thresholds.trafficThreshold());
            updateAlertState(serverId, TRAFFIC_ALERT_KEY, trafficHigh);
            if (trafficHigh && activateAlert(serverId, TRAFFIC_ALERT_KEY)) {
                items.add(buildTrafficAlert(server, currentTrafficRatio, totalTrafficBytes));
            }
        } catch (Exception e) {
            clearServerAlerts(server.getId());
            log.error("检查服务器监控提醒失败，serverId={}, error={}", server.getId(), e.getMessage(), e);
        }

        return items;
    }

    private MonitorAlertThresholds loadThresholds() {
        return new MonitorAlertThresholds(
                getCpuThreshold(),
                getMemoryThreshold(),
                getTrafficThreshold(),
                getContinuousMinutes()
        );
    }

    private void clearServerAlerts(Long serverId) {
        if (serverId == null) {
            return;
        }
        activeAlerts.remove(buildAlertKey(serverId, CPU_ALERT_KEY));
        activeAlerts.remove(buildAlertKey(serverId, MEMORY_ALERT_KEY));
        activeAlerts.remove(buildAlertKey(serverId, TRAFFIC_ALERT_KEY));
    }

    private void updateAlertState(Long serverId, String alertType, boolean active) {
        String key = buildAlertKey(serverId, alertType);
        if (!active) {
            activeAlerts.remove(key);
        }
    }

    private boolean activateAlert(Long serverId, String alertType) {
        String key = buildAlertKey(serverId, alertType);
        return activeAlerts.putIfAbsent(key, Boolean.TRUE) == null;
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
        if (name == null || name.isBlank()) {
            return ip;
        }
        return name + " (" + ip + ")";
    }

    private String buildAlertKey(Long serverId, String alertType) {
        return alertType + ":" + serverId;
    }

    private double normalizeThreshold(double value) {
        if (value <= 0) {
            return 1.0;
        }
        if (value > 1) {
            return value / 100.0;
        }
        return value;
    }

    private double normalizePercentThreshold(double value) {
        return normalizeThreshold(value) * 100D;
    }

    private String formatPercentValue(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.2f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.2f MB", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format(Locale.ROOT, "%.2f GB", gb);
        }
        double tb = gb / 1024.0;
        return String.format(Locale.ROOT, "%.2f TB", tb);
    }

    private double getCpuThreshold() {
        return systemConfigService.getDoubleValue("airopscat.server.monitor.alert.cpu-threshold", 0.9D);
    }

    private double getMemoryThreshold() {
        return systemConfigService.getDoubleValue("airopscat.server.monitor.alert.memory-threshold", 0.95D);
    }

    private double getTrafficThreshold() {
        return systemConfigService.getDoubleValue("airopscat.server.monitor.alert.traffic-threshold", 0.85D);
    }

    private int getContinuousMinutes() {
        return Math.max(1, systemConfigService.getIntValue("airopscat.server.monitor.alert.continuous-minutes", 30));
    }

    private record MonitorAlertThresholds(double cpuThreshold,
                                          double memoryThreshold,
                                          double trafficThreshold,
                                          int continuousMinutes) {
    }
}
