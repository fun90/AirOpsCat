package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.entity.ServerTrafficStats;
import com.fun90.airopscat.repository.ServerTrafficStatsRepository;
import com.fun90.airopscat.util.TrafficPeriodUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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
    public ServerTrafficStats saveOrUpdateTrafficStats(Long serverId, LocalDateTime bandwidthDate, long uploadBytes, long downloadBytes) {
        ServerTrafficStats stats = getOrCreateCurrentPeriodStats(serverId, bandwidthDate, LocalDateTime.now());
        stats.setUploadBytes(defaultLong(stats.getUploadBytes()) + uploadBytes);
        stats.setDownloadBytes(defaultLong(stats.getDownloadBytes()) + downloadBytes);
        return stats;
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
            dto.setTrafficStatsId(stats.getId());
            dto.setTrafficUploadBytes(stats.getUploadBytes());
            dto.setTrafficDownloadBytes(stats.getDownloadBytes());
            dto.setTrafficTotalBytes(stats.getUploadBytes() + stats.getDownloadBytes());
            dto.setTrafficPeriodStart(stats.getPeriodStart());
            dto.setTrafficPeriodEnd(stats.getPeriodEnd());
        }
    }

    @Transactional
    public ServerTrafficStats calibrateTrafficStats(Long trafficStatsId, LocalDateTime periodStartDate, LocalDateTime periodEndDate,
                                                    long uploadBytes, long downloadBytes) {
        ServerTrafficStats stats = serverTrafficStatsRepository.findById(trafficStatsId);
        if (stats == null) {
            return null;
        }
        stats.setPeriodStart(periodStartDate);
        stats.setPeriodEnd(periodEndDate);
        stats.setUploadBytes(uploadBytes);
        stats.setDownloadBytes(downloadBytes);
        return stats;
    }

    public ServerTrafficStats getCurrentPeriodStats(Long serverId, LocalDateTime now) {
        List<ServerTrafficStats> existingStats = serverTrafficStatsRepository.findByServerIdAndCurrentTime(serverId, now);
        if (existingStats.isEmpty()) {
            return null;
        }
        return existingStats.getFirst();
    }

    @Transactional
    public ServerTrafficStats getOrCreateCurrentPeriodStats(Long serverId, LocalDateTime bandwidthDate, LocalDateTime now) {
        LocalDateTime periodStart = TrafficPeriodUtils.resolveServerPeriodStart(now, bandwidthDate);
        LocalDateTime periodEnd = TrafficPeriodUtils.resolveServerPeriodEnd(now, bandwidthDate);
        ServerTrafficStats existing = serverTrafficStatsRepository.findByServerIdAndPeriod(serverId, periodStart, periodEnd);
        if (existing != null) {
            return existing;
        }

        ServerTrafficStats stats = new ServerTrafficStats();
        stats.setServerId(serverId);
        stats.setPeriodStart(periodStart);
        stats.setPeriodEnd(periodEnd);
        stats.setUploadBytes(0L);
        stats.setDownloadBytes(0L);
        stats.setMonitorUploadAdjustmentBytes(0L);
        stats.setMonitorDownloadAdjustmentBytes(0L);
        serverTrafficStatsRepository.persist(stats);
        return stats;
    }

    @Transactional
    public void calibrateCurrentPeriodMonitorTraffic(Long serverId, LocalDateTime bandwidthDate, LocalDateTime now,
                                                     long uploadAdjustmentBytes, long downloadAdjustmentBytes) {
        ServerTrafficStats stats = getOrCreateCurrentPeriodStats(serverId, bandwidthDate, now);
        stats.setMonitorUploadAdjustmentBytes(uploadAdjustmentBytes);
        stats.setMonitorDownloadAdjustmentBytes(downloadAdjustmentBytes);
    }

    @Transactional
    public long deleteByServerId(Long serverId) {
        return serverTrafficStatsRepository.deleteByServerId(serverId);
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

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

}
