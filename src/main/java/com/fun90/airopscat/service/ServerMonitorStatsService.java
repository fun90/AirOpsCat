package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.ServerMonitorChartDto;
import com.fun90.airopscat.model.dto.ServerMonitorPointDto;
import com.fun90.airopscat.model.dto.ServerMonitorSummaryDto;
import com.fun90.airopscat.model.dto.VnstatPointDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerMonitorStats;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.ServerMonitorStatsRepository;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.ssh.ServerSshConfigFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Slf4j
@ApplicationScoped
public class ServerMonitorStatsService {
    private static final String REMOTE_COLLECTOR_PATH = "/usr/local/bin/airopscat-server-monitor-collect";
    private static final String COLLECTOR_MISSING_MARKER = "__AIROPSCAT_MONITOR_COLLECTOR_MISSING__=1";
    private static final int DEFAULT_CLEANUP_BATCH_SIZE = 1000;

    @Inject
    ServerMonitorStatsRepository serverMonitorStatsRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    ServerSshConfigFactory serverSshConfigFactory;

    @Inject
    ServerHostService serverHostService;

    @Inject
    ServerVnstatStatsService serverVnstatStatsService;

    @Inject
    SystemConfigService systemConfigService;

    @Transactional
    public ServerMonitorSummaryDto collectAndSave(Server server) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> metrics = executeRemoteCollection(server);
        if (metrics.isEmpty()) {
            return null;
        }

        ServerMonitorStats stats = new ServerMonitorStats();
        stats.setServerId(server.getId());
        stats.setCpuUsage(parseDouble(metrics.get("cpuUsage")));
        stats.setMemoryUsage(parseDouble(metrics.get("memoryUsage")));
        stats.setMemoryUsedBytes(parseLong(metrics.get("memoryUsedBytes")));
        stats.setMemoryTotalBytes(parseLong(metrics.get("memoryTotalBytes")));
        stats.setNetworkRxRateBytes(parseLong(metrics.get("networkRxRateBytes")));
        stats.setNetworkTxRateBytes(parseLong(metrics.get("networkTxRateBytes")));
        stats.setSampleTime(now);
        serverMonitorStatsRepository.persist(stats);

        return toSummaryDto(server, stats, getVnstatTraffic(server.getId(), now));
    }

    @Transactional
    public ServerMonitorSummaryDto getLatestSummary(Server server) {
        Integer cpuCores = resolveCpuCores(server);
        ServerMonitorStats latest = serverMonitorStatsRepository.findLatestByServerId(server.getId());
        LocalDateTime now = LocalDateTime.now();
        VnstatTraffic vnstatTraffic = getVnstatTraffic(server.getId(), now);

        if (latest == null) {
            ServerMonitorSummaryDto dto = new ServerMonitorSummaryDto();
            dto.setServerId(server.getId());
            dto.setServerName(server.getName());
            dto.setServerIp(server.getIp());
            dto.setServerHost(serverHostService.resolvePrimaryHost(server));
            dto.setCpuCores(cpuCores);
            dto.setNetworkRxBytes(vnstatTraffic.rxBytes());
            dto.setNetworkTxBytes(vnstatTraffic.txBytes());
            dto.setVnstatAvailable(vnstatTraffic.available());
            dto.setDataAvailable(false);
            return dto;
        }

        ServerMonitorSummaryDto dto = toSummaryDto(server, latest, vnstatTraffic);
        dto.setCpuCores(cpuCores);
        return dto;
    }

    @Transactional
    public ServerMonitorChartDto getChartData(Server server, int hours) {
        int safeHours = Math.clamp(hours, 1, 24 * 7);
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(safeHours);
        List<ServerMonitorStats> statsList = serverMonitorStatsRepository
                .findByServerIdAndSampleTimeBetween(server.getId(), startTime, endTime);
        List<ServerVnstatStatsService.VnstatSnapshotData> vnstatSnapshots =
                serverVnstatStatsService.getSnapshots(server.getId(), startTime);

        ServerMonitorChartDto dto = new ServerMonitorChartDto();
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setServerIp(server.getIp());
        dto.setServerHost(serverHostService.resolvePrimaryHost(server));
        dto.setHours(safeHours);
        dto.setPoints(toPointDtos(statsList));
        dto.setVnstatPoints(toVnstatPointDtos(vnstatSnapshots));
        return dto;
    }

    @Transactional
    public boolean isCpuUsageHighForDuration(Long serverId, LocalDateTime referenceTime,
                                             double threshold, int durationMinutes) {
        return hasContinuousUsageThresholdExceeded(
                serverId, referenceTime, threshold, durationMinutes, ServerMonitorStats::getCpuUsage);
    }

    @Transactional
    public boolean isMemoryUsageHighForDuration(Long serverId, LocalDateTime referenceTime,
                                                double threshold, int durationMinutes) {
        return hasContinuousUsageThresholdExceeded(
                serverId, referenceTime, threshold, durationMinutes, ServerMonitorStats::getMemoryUsage);
    }

    @Transactional
    public long getCurrentPeriodTotalTrafficBytes(Server server, LocalDateTime sampleTime) {
        VnstatTraffic traffic = getVnstatTraffic(server.getId(), sampleTime);
        return traffic.rxBytes() + traffic.txBytes();
    }

    @Transactional
    public long deleteByServerId(Long serverId) {
        serverVnstatStatsService.deleteByServerId(serverId);
        return serverMonitorStatsRepository.deleteByServerId(serverId);
    }

    @Transactional
    public long cleanupExpiredStats() {
        int retentionDays = Math.max(systemConfigService.getIntValue("airopscat.server.monitor.retention-days", 30), 1);
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        int batchSize = Math.max(
                systemConfigService.getIntValue("airopscat.server.monitor.cleanup.batch-size", DEFAULT_CLEANUP_BATCH_SIZE), 1);
        long totalDeleted = 0L;
        int rounds = 0;

        while (true) {
            int deleted = serverMonitorStatsRepository.deleteBySampleTimeBeforeBatch(cutoffTime, batchSize);
            if (deleted <= 0) break;
            totalDeleted += deleted;
            rounds++;
            if (deleted < batchSize) break;
        }

        long vnstatDeleted = serverVnstatStatsService.cleanupExpiredStats(cutoffTime);
        log.info("服务器监控历史清理完成，保留天数: {}, 截止时间: {}, 批大小: {}, 批次数: {}, 删除总数: {}, vnstat删除: {}",
                retentionDays, cutoffTime, batchSize, rounds, totalDeleted, vnstatDeleted);
        return totalDeleted;
    }

    private Map<String, String> executeRemoteCollection(Server server) {
        try (SshConnection connection = sshConnectionService.createConnection(serverSshConfigFactory.create(server, 10000))) {
            CommandResult commandResult = connection.executeCommand(
                    "if [ ! -x " + quoteShell(REMOTE_COLLECTOR_PATH) + " ]; then echo " + quoteShell(COLLECTOR_MISSING_MARKER)
                            + "; exit 0; fi; " + quoteShell(REMOTE_COLLECTOR_PATH)
            );

            if (!commandResult.isSuccess()) {
                throw new IllegalStateException("执行服务器监控采集脚本失败: " + commandResult.getStderr());
            }

            if (commandResult.getStdout() != null && commandResult.getStdout().contains(COLLECTOR_MISSING_MARKER)) {
                log.debug("服务器 {} 未安装监控采集脚本，跳过本次采集", server.getId());
                return Map.of();
            }

            return parseMetrics(commandResult.getStdout());
        } catch (Exception e) {
            throw new IllegalStateException("连接服务器采集监控数据失败: " + server.getIp(), e);
        }
    }

    private Map<String, String> parseMetrics(String output) {
        Map<String, String> result = new HashMap<>();
        if (output == null || output.isBlank()) {
            return result;
        }
        for (String line : output.split("\\R")) {
            int idx = line.indexOf('=');
            if (idx <= 0) continue;
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            if (!key.isEmpty()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private VnstatTraffic getVnstatTraffic(Long serverId, LocalDateTime referenceTime) {
        Optional<ServerVnstatStatsService.VnstatPeriodTraffic> opt =
                serverVnstatStatsService.getPeriodTraffic(serverId, referenceTime.getYear(), referenceTime.getMonthValue());
        return opt.map(t -> new VnstatTraffic(t.rxBytes(), t.txBytes(), true))
                .orElse(new VnstatTraffic(0L, 0L, false));
    }

    private ServerMonitorSummaryDto toSummaryDto(Server server, ServerMonitorStats stats, VnstatTraffic vnstatTraffic) {
        ServerMonitorSummaryDto dto = new ServerMonitorSummaryDto();
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setServerIp(server.getIp());
        dto.setServerHost(serverHostService.resolvePrimaryHost(server));
        dto.setSampleTime(stats.getSampleTime());
        dto.setCpuUsage(stats.getCpuUsage());
        dto.setMemoryUsage(stats.getMemoryUsage());
        dto.setMemoryUsedBytes(stats.getMemoryUsedBytes());
        dto.setMemoryTotalBytes(stats.getMemoryTotalBytes());
        dto.setNetworkRxBytes(vnstatTraffic.rxBytes());
        dto.setNetworkTxBytes(vnstatTraffic.txBytes());
        dto.setVnstatAvailable(vnstatTraffic.available());
        dto.setNetworkRxRateBytes(stats.getNetworkRxRateBytes());
        dto.setNetworkTxRateBytes(stats.getNetworkTxRateBytes());
        dto.setDataAvailable(true);
        return dto;
    }

    private List<ServerMonitorPointDto> toPointDtos(List<ServerMonitorStats> statsList) {
        return statsList.stream().map(this::toPointDto).toList();
    }

    private ServerMonitorPointDto toPointDto(ServerMonitorStats stats) {
        ServerMonitorPointDto dto = new ServerMonitorPointDto();
        dto.setSampleTime(stats.getSampleTime());
        dto.setCpuUsage(stats.getCpuUsage());
        dto.setMemoryUsage(stats.getMemoryUsage());
        dto.setMemoryUsedBytes(stats.getMemoryUsedBytes());
        dto.setMemoryTotalBytes(stats.getMemoryTotalBytes());
        dto.setNetworkRxRateBytes(stats.getNetworkRxRateBytes());
        dto.setNetworkTxRateBytes(stats.getNetworkTxRateBytes());
        return dto;
    }

    private List<VnstatPointDto> toVnstatPointDtos(List<ServerVnstatStatsService.VnstatSnapshotData> snapshots) {
        return snapshots.stream().map(s -> {
            VnstatPointDto dto = new VnstatPointDto();
            dto.setSampledAt(s.sampledAt());
            dto.setRxBytes(s.rxBytes());
            dto.setTxBytes(s.txBytes());
            return dto;
        }).toList();
    }

    private Integer resolveCpuCores(Server server) {
        if (server.getCpuCores() != null && server.getCpuCores() > 0) {
            return server.getCpuCores();
        }
        Integer cpuCores = fetchCpuCores(server);
        if (cpuCores > 0) {
            Server managedServer = serverRepository.findById(server.getId());
            if (managedServer != null) {
                managedServer.setCpuCores(cpuCores);
                serverRepository.getEntityManager().flush();
            }
            server.setCpuCores(cpuCores);
        }
        return cpuCores;
    }

    private Integer fetchCpuCores(Server server) {
        try (SshConnection connection = sshConnectionService.createConnection(serverSshConfigFactory.create(server, 10000))) {
            CommandResult commandResult = connection.executeCommand("nproc");
            if (!commandResult.isSuccess()) {
                log.warn("获取 CPU 核数失败，serverId={}, stderr={}", server.getId(), commandResult.getStderr());
                return 0;
            }
            return parseInteger(commandResult.getStdout().trim());
        } catch (Exception e) {
            log.warn("获取 CPU 核数异常，serverId={}, error={}", server.getId(), e.getMessage());
            return 0;
        }
    }

    private boolean hasContinuousUsageThresholdExceeded(Long serverId, LocalDateTime referenceTime,
                                                        double threshold, int durationMinutes,
                                                        Function<ServerMonitorStats, Double> usageExtractor) {
        long monitorRefreshSeconds = getMonitorRefreshSeconds();
        if (serverId == null || referenceTime == null || durationMinutes <= 0 || monitorRefreshSeconds <= 0) {
            return false;
        }
        long durationSeconds = durationMinutes * 60L + monitorRefreshSeconds * 2;
        int requiredSampleCount = (int) Math.ceil(durationSeconds / (double) monitorRefreshSeconds / 2);
        LocalDateTime windowStart = referenceTime.minusSeconds(durationSeconds);
        List<ServerMonitorStats> latestStats = serverMonitorStatsRepository.findLatestListByServerId(serverId, requiredSampleCount);
        if (latestStats.size() < requiredSampleCount) return false;

        ServerMonitorStats latest = latestStats.getFirst();
        if (latest.getSampleTime() == null
                || latest.getSampleTime().isBefore(referenceTime.minusSeconds(monitorRefreshSeconds * 2L))) {
            return false;
        }

        List<ServerMonitorStats> effectiveStats = latestStats.stream()
                .filter(s -> s.getSampleTime() != null && !s.getSampleTime().isBefore(windowStart))
                .toList();
        if (effectiveStats.isEmpty()) return false;

        for (ServerMonitorStats s : effectiveStats) {
            Double usage = usageExtractor.apply(s);
            if (usage == null || usage < threshold) return false;
        }
        return true;
    }

    private long getMonitorRefreshSeconds() {
        return Math.max(1, systemConfigService.getIntValue("airopscat.server.monitor.refresh-minutes", 1)) * 60L;
    }

    private String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("解析监控长整型指标失败: {}", value);
            return 0L;
        }
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) return 0D;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            log.warn("解析监控浮点指标失败: {}", value);
            return 0D;
        }
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("解析监控整型指标失败: {}", value);
            return 0;
        }
    }

    private record VnstatTraffic(long rxBytes, long txBytes, boolean available) {}
}
