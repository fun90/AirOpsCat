package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerTrafficStats;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.ServerTrafficStatsRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ServerTrafficThresholdNotifier implements MonitorNotifier {

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    @Inject
    ServerRepository serverRepository;

    @Inject
    ServerTrafficStatsRepository serverTrafficStatsRepository;

    @ConfigProperty(name = "airopscat.server.traffic.threshold", defaultValue = "0.9")
    double threshold;

    @Override
    public String getType() {
        return "server-traffic";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 服务器流量提醒";
    }

    @Override
    public List<String> findItems(LocalDate today) {
        List<Server> servers = serverRepository.findByDisabled(0);
        if (servers.isEmpty()) {
            return List.of();
        }

        Map<Long, ServerTrafficStats> statsMap = loadCurrentStats(servers);
        double effectiveThreshold = normalizeThreshold(threshold);

        return servers.stream()
                .filter(server -> server.getDisabled() == null || server.getDisabled() == 0)
                .filter(server -> server.getBandwidth() != null && server.getBandwidth() > 0)
                .map(server -> buildItem(server, statsMap.get(server.getId()), effectiveThreshold))
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }

    @Override
    public String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return String.join("\n", items);
    }

    private Map<Long, ServerTrafficStats> loadCurrentStats(List<Server> servers) {
        List<Long> ids = servers.stream()
                .map(Server::getId)
                .filter(id -> id != null)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<ServerTrafficStats> statsList =
                serverTrafficStatsRepository.findByServerIdsAndCurrentTime(ids, LocalDateTime.now());
        Map<Long, ServerTrafficStats> result = new HashMap<>();
        for (ServerTrafficStats stats : statsList) {
            result.put(stats.getServerId(), stats);
        }
        return result;
    }

    private String buildItem(Server server, ServerTrafficStats stats, double effectiveThreshold) {
        long usedBytes = 0L;
        if (stats != null) {
            usedBytes = stats.getUploadBytes() + stats.getDownloadBytes();
        }

        long limitBytes = server.getBandwidth().longValue() * BYTES_PER_GB;
        if (limitBytes <= 0) {
            return null;
        }

        double usageRatio = usedBytes / (double) limitBytes;
        if (usageRatio < effectiveThreshold) {
            return null;
        }

        String name = server.getName();
        String ip = server.getIp();
        String serverLabel = (name != null && !name.isBlank()) ? name + " (" + ip + ")" : ip;

        long percent = Math.round(usageRatio * 100);
        return serverLabel + " 使用 " + percent + "% (" + formatBytes(usedBytes) + " / " + formatBytes(limitBytes) + ")";
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

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.2f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.2f MB", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format("%.2f GB", gb);
        }
        double tb = gb / 1024.0;
        return String.format("%.2f TB", tb);
    }
}
