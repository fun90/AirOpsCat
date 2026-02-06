package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.entity.ServerTrafficStats;
import com.fun90.airopscat.repository.ServerTrafficStatsRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class ServerTrafficStatsService {

    @Inject
    ServerTrafficStatsRepository serverTrafficStatsRepository;

    @Transactional
    public ServerTrafficStats saveOrUpdateTrafficStats(Long serverId, LocalDate expireDate, long uploadBytes, long downloadBytes) {
        LocalDateTime now = LocalDateTime.now();
        List<ServerTrafficStats> existingStats = serverTrafficStatsRepository.findByServerIdAndCurrentTime(serverId, now);

        if (!existingStats.isEmpty()) {
            ServerTrafficStats stats = existingStats.getFirst();
            stats.setUploadBytes(stats.getUploadBytes() + uploadBytes);
            stats.setDownloadBytes(stats.getDownloadBytes() + downloadBytes);
            return stats;
        }

        ServerTrafficStats newStats = new ServerTrafficStats();
        newStats.setServerId(serverId);
        LocalDateTime periodStart = calculatePeriodStart(now);
        newStats.setPeriodStart(periodStart);
        newStats.setPeriodEnd(calculatePeriodEnd(periodStart));
        newStats.setUploadBytes(uploadBytes);
        newStats.setDownloadBytes(downloadBytes);
        serverTrafficStatsRepository.persist(newStats);
        return newStats;
    }

    public void fillCurrentPeriodTraffic(List<ServerDto> serverDtos) {
        if (serverDtos == null || serverDtos.isEmpty()) {
            return;
        }

        List<Long> serverIds = serverDtos.stream()
                .map(ServerDto::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, ServerTrafficStats> statsMap = getCurrentPeriodStatsMap(serverIds, LocalDateTime.now());
        for (ServerDto dto : serverDtos) {
            ServerTrafficStats stats = statsMap.get(dto.getId());
            if (stats == null) {
                continue;
            }
            dto.setTrafficUploadBytes(stats.getUploadBytes());
            dto.setTrafficDownloadBytes(stats.getDownloadBytes());
            dto.setTrafficTotalBytes(stats.getUploadBytes() + stats.getDownloadBytes());
            dto.setTrafficPeriodStart(stats.getPeriodStart());
            dto.setTrafficPeriodEnd(stats.getPeriodEnd());
        }
    }

    private Map<Long, ServerTrafficStats> getCurrentPeriodStatsMap(List<Long> serverIds, LocalDateTime now) {
        List<ServerTrafficStats> statsList = serverTrafficStatsRepository.findByServerIdsAndCurrentTime(serverIds, now);
        Map<Long, ServerTrafficStats> result = new HashMap<>();
        for (ServerTrafficStats stats : statsList) {
            ServerTrafficStats existing = result.get(stats.getServerId());
            if (existing == null || stats.getPeriodEnd().isAfter(existing.getPeriodEnd())) {
                result.put(stats.getServerId(), stats);
            }
        }
        return result;
    }

    private LocalDateTime calculatePeriodStart(LocalDateTime now) {
        LocalDate monthStart = now.toLocalDate().withDayOfMonth(1);
        return monthStart.atStartOfDay();
    }

    private LocalDateTime calculatePeriodEnd(LocalDateTime periodStart) {
        return periodStart.toLocalDate().plusMonths(1).atStartOfDay().minusNanos(1);
    }
}
