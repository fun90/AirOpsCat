package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerTrafficStats;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.ServerTrafficStatsRepository;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.SystemConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ServerTrafficThresholdNotifier implements MonitorNotifier {

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final String ALERT_TYPE = "server-traffic-threshold";
    private static final String RESOURCE_TYPE = "server";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RECOVERED = "RECOVERED";
    private static final String SEVERITY_WARNING = "WARNING";

    @Inject
    ServerRepository serverRepository;

    @Inject
    ServerTrafficStatsRepository serverTrafficStatsRepository;

    @Inject
    AlertStateRepository alertStateRepository;

    @Inject
    BarkService barkService;

    @Inject
    SystemConfigService systemConfigService;

    @Override
    public String getType() {
        return "server-traffic";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 服务器流量提醒";
    }

    @Override
    @Transactional
    public List<String> findItems(LocalDate today) {
        List<Server> servers = serverRepository.findByDisabled(0);
        if (servers.isEmpty()) return List.of();

        Map<Long, ServerTrafficStats> statsMap = loadCurrentStats(servers);
        double effectiveThreshold = normalizeThreshold(
                systemConfigService.getDoubleValue("airopscat.server.monitor.alert.traffic-threshold", 0.9));

        LocalDateTime now = LocalDateTime.now();
        Set<Long> exceededIds = new HashSet<>();

        for (Server server : servers) {
            if (server.getId() == null || server.getBandwidth() == null || server.getBandwidth() <= 0) continue;

            ServerTrafficStats stats = statsMap.get(server.getId());
            long usedBytes = stats != null ? stats.getUploadBytes() + stats.getDownloadBytes() : 0L;
            long limitBytes = server.getBandwidth().longValue() * BYTES_PER_GB;
            double usageRatio = usedBytes / (double) limitBytes;

            if (usageRatio >= effectiveThreshold) {
                exceededIds.add(server.getId());
                triggerAlert(server, usedBytes, limitBytes, usageRatio, now);
            } else {
                recoverAlert(server, now);
            }
        }

        return List.of();
    }

    @Override
    public String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        return String.join("\n", items);
    }

    private void triggerAlert(Server server, long usedBytes, long limitBytes, double usageRatio, LocalDateTime now) {
        String fp = fingerprint(server);
        AlertState state = alertStateRepository
                .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, server.getId(), fp)
                .orElseGet(() -> newAlertState(server, fp, now));

        if (!STATUS_ACTIVE.equals(state.getStatus())) {
            state.setStatus(STATUS_ACTIVE);
            state.setFirstTriggeredTime(now);
            state.setRecoveredTime(null);
        }
        state.setLastTriggeredTime(now);
        state.setTriggerCount((state.getTriggerCount() == null ? 0 : state.getTriggerCount()) + 1);
        state.setLastValue(usageRatio * 100);
        state.setThresholdValue(effectiveThresholdPercent());
        long percent = Math.round(usageRatio * 100);
        String summary = displayName(server) + " 使用 " + percent + "% ("
                + formatBytes(usedBytes) + " / " + formatBytes(limitBytes) + ")";
        state.setSummary(summary);
        persistIfNew(state);

        if (shouldNotify(state, now)) {
            barkService.sendWarningNotification(getTitle(), summary);
            state.setLastNotifiedTime(now);
        }
    }

    private void recoverAlert(Server server, LocalDateTime now) {
        alertStateRepository
                .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, server.getId(), fingerprint(server))
                .filter(s -> STATUS_ACTIVE.equals(s.getStatus()))
                .ifPresent(s -> {
                    s.setStatus(STATUS_RECOVERED);
                    s.setRecoveredTime(now);
                    s.setLastTriggeredTime(now);
                });
    }

    private boolean shouldNotify(AlertState state, LocalDateTime now) {
        int minutes = Math.max(0, systemConfigService.getIntValue(
                "airopscat.server.traffic.alert.min-interval-minutes", 60));
        return state.getLastNotifiedTime() == null
                || !state.getLastNotifiedTime().plusMinutes(minutes).isAfter(now);
    }

    private double effectiveThresholdPercent() {
        return normalizeThreshold(
                systemConfigService.getDoubleValue("airopscat.server.monitor.alert.traffic-threshold", 0.9)) * 100;
    }

    private AlertState newAlertState(Server server, String fingerprint, LocalDateTime now) {
        AlertState state = new AlertState();
        state.setAlertType(ALERT_TYPE);
        state.setResourceType(RESOURCE_TYPE);
        state.setResourceId(server.getId());
        state.setResourceKey(fingerprint);
        state.setFingerprint(fingerprint);
        state.setStatus(STATUS_ACTIVE);
        state.setSeverity(SEVERITY_WARNING);
        state.setFirstTriggeredTime(now);
        state.setTriggerCount(0);
        return state;
    }

    private void persistIfNew(AlertState state) {
        if (state.getId() == null) alertStateRepository.persist(state);
    }

    private Map<Long, ServerTrafficStats> loadCurrentStats(List<Server> servers) {
        List<Long> ids = servers.stream().map(Server::getId).filter(id -> id != null).toList();
        if (ids.isEmpty()) return Map.of();
        List<ServerTrafficStats> statsList =
                serverTrafficStatsRepository.findByServerIdsAndCurrentTime(ids, LocalDateTime.now());
        Map<Long, ServerTrafficStats> result = new HashMap<>();
        for (ServerTrafficStats stats : statsList) {
            result.put(stats.getServerId(), stats);
        }
        return result;
    }

    private String fingerprint(Server server) {
        return server.getIp() != null ? server.getIp() : String.valueOf(server.getId());
    }

    private String displayName(Server server) {
        String name = server.getName();
        String ip = server.getIp();
        return (name != null && !name.isBlank()) ? name + " (" + ip + ")" : ip;
    }

    private double normalizeThreshold(double value) {
        if (value <= 0) return 1.0;
        if (value > 1) return value / 100.0;
        return value;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.2f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return String.format("%.2f GB", gb);
        return String.format("%.2f TB", gb / 1024.0);
    }
}
