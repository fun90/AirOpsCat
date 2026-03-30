package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.ServerMonitorChartDto;
import com.fun90.airopscat.model.dto.ServerMonitorPointDto;
import com.fun90.airopscat.model.dto.ServerMonitorSummaryDto;
import com.fun90.airopscat.model.dto.ServerMonitorTrafficCalibrationDto;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerMonitorStats;
import com.fun90.airopscat.model.entity.ServerTrafficStats;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.ServerMonitorStatsRepository;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.util.TrafficPeriodUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class ServerMonitorStatsService {
    private static final String REMOTE_COLLECTOR_PATH = "/usr/local/bin/airopscat-server-monitor-collect";
    private static final String COLLECTOR_MISSING_MARKER = "__AIROPSCAT_MONITOR_COLLECTOR_MISSING__=1";

    @Inject
    ServerMonitorStatsRepository serverMonitorStatsRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    ServerHostService serverHostService;

    @Inject
    ServerTrafficStatsService serverTrafficStatsService;

    @ConfigProperty(name = "airopscat.server.monitor.refresh-minutes", defaultValue = "1")
    int monitorRefreshMinutes;

    @ConfigProperty(name = "airopscat.server.monitor.alert.max-missing-samples", defaultValue = "5")
    int maxMissingSamples;

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
        stats.setNetworkRxBytes(parseLong(metrics.get("networkRxBytes")));
        stats.setNetworkTxBytes(parseLong(metrics.get("networkTxBytes")));
        stats.setSampleTime(now);
        fillNetworkIncrement(stats);
        stats.setNetworkRxRateBytes(parseLong(metrics.get("networkRxRateBytes")));
        stats.setNetworkTxRateBytes(parseLong(metrics.get("networkTxRateBytes")));
        serverMonitorStatsRepository.persist(stats);

        return toSummaryDto(server, stats, calculatePeriodTraffic(server, now));
    }

    @Transactional
    public ServerMonitorSummaryDto getLatestSummary(Server server) {
        Integer cpuCores = resolveCpuCores(server);
        ServerMonitorStats latest = serverMonitorStatsRepository.findLatestByServerId(server.getId());
        if (latest == null) {
            LocalDateTime now = LocalDateTime.now();
            PeriodTraffic currentTraffic = calculatePeriodTraffic(server, now);
            ServerMonitorSummaryDto dto = new ServerMonitorSummaryDto();
            dto.setServerId(server.getId());
            dto.setServerName(server.getName());
            dto.setServerIp(server.getIp());
            dto.setServerHost(serverHostService.resolvePrimaryHost(server));
            dto.setCpuCores(cpuCores);
            dto.setNetworkRxBytes(currentTraffic.rxBytes());
            dto.setNetworkTxBytes(currentTraffic.txBytes());
            dto.setTrafficPeriodStart(resolveBandwidthPeriodStart(server, now));
            dto.setTrafficPeriodEnd(resolveBandwidthPeriodEnd(server, now));
            dto.setDataAvailable(false);
            return dto;
        }
        ServerMonitorSummaryDto dto = toSummaryDto(server, latest, calculatePeriodTraffic(server, latest.getSampleTime()));
        dto.setCpuCores(cpuCores);
        return dto;
    }

    public ServerMonitorChartDto getChartData(Server server, int hours) {
        int safeHours = Math.clamp(hours, 1, 24 * 7);
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(safeHours);
        LocalDateTime periodStart = resolveBandwidthPeriodStart(server, endTime);
        MonitorTrafficAdjustment adjustment = getMonitorTrafficAdjustment(server, endTime);
        List<ServerMonitorStats> statsList = serverMonitorStatsRepository.findByServerIdAndSampleTimeBetween(server.getId(), startTime, endTime);
        long baseRx = startTime.isBefore(periodStart)
                ? 0L
                : adjustment.downloadBytes() + defaultLong(serverMonitorStatsRepository.sumRxIncrement(server.getId(), periodStart, startTime));
        long baseTx = startTime.isBefore(periodStart)
                ? 0L
                : adjustment.uploadBytes() + defaultLong(serverMonitorStatsRepository.sumTxIncrement(server.getId(), periodStart, startTime));

        ServerMonitorChartDto dto = new ServerMonitorChartDto();
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setServerIp(server.getIp());
        dto.setServerHost(serverHostService.resolvePrimaryHost(server));
        dto.setHours(safeHours);
        dto.setPoints(toPointDtos(statsList, baseRx, baseTx, periodStart));
        return dto;
    }

    public boolean isCpuUsageHighForDuration(Long serverId, LocalDateTime referenceTime,
                                             double threshold, int durationMinutes) {
        return hasContinuousUsageThresholdExceeded(
                serverId,
                referenceTime,
                threshold,
                durationMinutes,
                ServerMonitorStats::getCpuUsage
        );
    }

    public boolean isMemoryUsageHighForDuration(Long serverId, LocalDateTime referenceTime,
                                                double threshold, int durationMinutes) {
        return hasContinuousUsageThresholdExceeded(
                serverId,
                referenceTime,
                threshold,
                durationMinutes,
                ServerMonitorStats::getMemoryUsage
        );
    }

    public long getCurrentPeriodTotalTrafficBytes(Server server, LocalDateTime sampleTime) {
        PeriodTraffic periodTraffic = calculatePeriodTraffic(server, sampleTime);
        return periodTraffic.rxBytes() + periodTraffic.txBytes();
    }

    @Transactional
    public ServerMonitorSummaryDto calibrateCurrentPeriod(Server server, ServerMonitorTrafficCalibrationDto calibrationDto) {
        Server managedServer = serverRepository.findById(server.getId());
        if (managedServer == null) {
            throw new IllegalArgumentException("服务器不存在: " + server.getId());
        }

        LocalDateTime now = LocalDateTime.now();
        RawPeriodTraffic rawPeriodTraffic = calculateRawPeriodTraffic(managedServer, now);
        long rawPeriodTx = rawPeriodTraffic.uploadBytes();
        long rawPeriodRx = rawPeriodTraffic.downloadBytes();
        long targetTx = calibrationDto.getUploadGb()
                .multiply(java.math.BigDecimal.valueOf(1024L * 1024L * 1024L))
                .longValue();
        long targetRx = calibrationDto.getDownloadGb()
                .multiply(java.math.BigDecimal.valueOf(1024L * 1024L * 1024L))
                .longValue();

        serverTrafficStatsService.calibrateCurrentPeriodMonitorTraffic(
                managedServer.getId(),
                managedServer.getBandwidthDate(),
                now,
                targetTx - rawPeriodTx,
                targetRx - rawPeriodRx
        );

        ServerMonitorStats latest = serverMonitorStatsRepository.findLatestByServerId(server.getId());
        if (latest == null) {
            ServerMonitorSummaryDto dto = new ServerMonitorSummaryDto();
            dto.setServerId(managedServer.getId());
            dto.setServerName(managedServer.getName());
            dto.setServerIp(managedServer.getIp());
            dto.setServerHost(serverHostService.resolvePrimaryHost(managedServer));
            dto.setCpuCores(resolveCpuCores(managedServer));
            dto.setNetworkTxBytes(targetTx);
            dto.setNetworkRxBytes(targetRx);
            dto.setTrafficPeriodStart(resolveBandwidthPeriodStart(managedServer, now));
            dto.setTrafficPeriodEnd(resolveBandwidthPeriodEnd(managedServer, now));
            dto.setDataAvailable(false);
            return dto;
        }

        ServerMonitorSummaryDto dto = toSummaryDto(managedServer, latest, calculatePeriodTraffic(managedServer, now));
        dto.setCpuCores(resolveCpuCores(managedServer));
        dto.setTrafficPeriodStart(resolveBandwidthPeriodStart(managedServer, now));
        dto.setTrafficPeriodEnd(resolveBandwidthPeriodEnd(managedServer, now));
        return dto;
    }

    @Transactional
    public long deleteByServerId(Long serverId) {
        return serverMonitorStatsRepository.deleteByServerId(serverId);
    }

    private Map<String, String> executeRemoteCollection(Server server) {
        try (SshConnection connection = sshConnectionService.createConnection(buildSshConfig(server))) {
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

        String[] lines = output.split("\\R");
        for (String line : lines) {
            int separatorIndex = line.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = line.substring(0, separatorIndex).trim();
            String value = line.substring(separatorIndex + 1).trim();
            if (!key.isEmpty()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private void fillNetworkIncrement(ServerMonitorStats stats) {
        ServerMonitorStats previous = serverMonitorStatsRepository.findPreviousByServerId(stats.getServerId(), stats.getSampleTime());
        if (previous == null) {
            stats.setNetworkRxIncrementBytes(0L);
            stats.setNetworkTxIncrementBytes(0L);
            return;
        }

        long currentRx = defaultLong(stats.getNetworkRxBytes());
        long currentTx = defaultLong(stats.getNetworkTxBytes());
        long previousRx = defaultLong(previous.getNetworkRxBytes());
        long previousTx = defaultLong(previous.getNetworkTxBytes());

        stats.setNetworkRxIncrementBytes(currentRx >= previousRx ? currentRx - previousRx : currentRx);
        stats.setNetworkTxIncrementBytes(currentTx >= previousTx ? currentTx - previousTx : currentTx);
    }

    private PeriodTraffic calculatePeriodTraffic(Server server, LocalDateTime sampleTime) {
        MonitorTrafficAdjustment adjustment = getMonitorTrafficAdjustment(server, sampleTime);
        RawPeriodTraffic rawPeriodTraffic = calculateRawPeriodTraffic(server, sampleTime);
        long rx = adjustment.downloadBytes() + rawPeriodTraffic.downloadBytes();
        long tx = adjustment.uploadBytes() + rawPeriodTraffic.uploadBytes();
        return new PeriodTraffic(rx, tx);
    }

    private RawPeriodTraffic calculateRawPeriodTraffic(Server server, LocalDateTime sampleTime) {
        LocalDateTime periodStart = resolveBandwidthPeriodStart(server, sampleTime);
        long downloadBytes = defaultLong(serverMonitorStatsRepository.sumRxIncrement(server.getId(), periodStart, sampleTime.plusNanos(1)));
        long uploadBytes = defaultLong(serverMonitorStatsRepository.sumTxIncrement(server.getId(), periodStart, sampleTime.plusNanos(1)));
        return new RawPeriodTraffic(downloadBytes, uploadBytes);
    }

    private MonitorTrafficAdjustment getMonitorTrafficAdjustment(Server server, LocalDateTime sampleTime) {
        ServerTrafficStats stats = serverTrafficStatsService.getCurrentPeriodStats(server.getId(), sampleTime);
        if (stats == null) {
            return new MonitorTrafficAdjustment(0L, 0L);
        }
        return new MonitorTrafficAdjustment(
                defaultLong(stats.getMonitorDownloadAdjustmentBytes()),
                defaultLong(stats.getMonitorUploadAdjustmentBytes())
        );
    }

    private LocalDateTime resolveBandwidthPeriodStart(Server server, LocalDateTime referenceTime) {
        return TrafficPeriodUtils.resolveServerPeriodStart(referenceTime, server.getBandwidthDate());
    }

    private LocalDateTime resolveBandwidthPeriodEnd(Server server, LocalDateTime referenceTime) {
        return TrafficPeriodUtils.resolveServerPeriodEnd(referenceTime, server.getBandwidthDate());
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
        try (SshConnection connection = sshConnectionService.createConnection(buildSshConfig(server))) {
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

    private String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private boolean hasContinuousUsageThresholdExceeded(Long serverId, LocalDateTime referenceTime,
                                                        double threshold, int durationMinutes,
                                                        java.util.function.Function<ServerMonitorStats, Double> usageExtractor) {
        long monitorRefreshSeconds = getMonitorRefreshSeconds();
        if (serverId == null || referenceTime == null || durationMinutes <= 0 || monitorRefreshSeconds <= 0) {
            return false;
        }

        long durationSeconds = durationMinutes * 60L;
        int requiredSampleCount = (int) Math.ceil(durationSeconds / (double) monitorRefreshSeconds) + 1;
        LocalDateTime windowStart = referenceTime.minusSeconds(durationSeconds);
        int toleratedMissingSamples = Math.max(maxMissingSamples, 0);
        List<ServerMonitorStats> latestStats = serverMonitorStatsRepository.findLatestListByServerId(
                serverId, requiredSampleCount + toleratedMissingSamples + 1);
        if (latestStats.isEmpty()) {
            return false;
        }

        ServerMonitorStats latest = latestStats.getFirst();
        if (latest.getSampleTime() == null
                || latest.getSampleTime().isBefore(referenceTime.minusSeconds(monitorRefreshSeconds * 2L))) {
            return false;
        }

        List<ServerMonitorStats> effectiveStats = latestStats.stream()
                .filter(stats -> stats.getSampleTime() != null)
                .filter(stats -> !stats.getSampleTime().isBefore(windowStart))
                .toList();
        if (effectiveStats.isEmpty()) {
            return false;
        }

        for (ServerMonitorStats stats : effectiveStats) {
            Double usage = usageExtractor.apply(stats);
            if (usage == null || usage < threshold) {
                return false;
            }
        }

        return true;
    }

    private long getMonitorRefreshSeconds() {
        return Math.max(1, monitorRefreshMinutes) * 60L;
    }

    private ServerMonitorSummaryDto toSummaryDto(Server server, ServerMonitorStats stats, PeriodTraffic periodTraffic) {
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
        dto.setNetworkRxBytes(periodTraffic.rxBytes());
        dto.setNetworkTxBytes(periodTraffic.txBytes());
        dto.setNetworkRxRateBytes(stats.getNetworkRxRateBytes());
        dto.setNetworkTxRateBytes(stats.getNetworkTxRateBytes());
        dto.setTrafficPeriodStart(resolveBandwidthPeriodStart(server, stats.getSampleTime()));
        dto.setTrafficPeriodEnd(resolveBandwidthPeriodEnd(server, stats.getSampleTime()));
        dto.setDataAvailable(true);
        return dto;
    }

    private List<ServerMonitorPointDto> toPointDtos(List<ServerMonitorStats> statsList, long baseRx, long baseTx, LocalDateTime periodStart) {
        long[] totals = {baseRx, baseTx};
        return statsList.stream()
                .map(stats -> {
                    if (!stats.getSampleTime().isBefore(periodStart)) {
                        totals[0] += defaultLong(stats.getNetworkRxIncrementBytes());
                        totals[1] += defaultLong(stats.getNetworkTxIncrementBytes());
                    }
                    return toPointDto(stats, totals[0], totals[1]);
                })
                .toList();
    }

    private ServerMonitorPointDto toPointDto(ServerMonitorStats stats, long cumulativeRx, long cumulativeTx) {
        ServerMonitorPointDto dto = new ServerMonitorPointDto();
        dto.setSampleTime(stats.getSampleTime());
        dto.setCpuUsage(stats.getCpuUsage());
        dto.setMemoryUsage(stats.getMemoryUsage());
        dto.setMemoryUsedBytes(stats.getMemoryUsedBytes());
        dto.setMemoryTotalBytes(stats.getMemoryTotalBytes());
        dto.setNetworkRxBytes(cumulativeRx);
        dto.setNetworkTxBytes(cumulativeTx);
        dto.setNetworkRxRateBytes(stats.getNetworkRxRateBytes());
        dto.setNetworkTxRateBytes(stats.getNetworkTxRateBytes());
        return dto;
    }

    private SshConfig buildSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort() != null ? server.getSshPort() : 22);
        sshConfig.setUsername(server.getUsername() == null || server.getUsername().isBlank() ? "root" : server.getUsername());
        sshConfig.setTimeout(10000);

        boolean isPassword = "PASSWORD".equalsIgnoreCase(server.getAuthType())
                || "password".equalsIgnoreCase(server.getAuthType());
        if (isPassword) {
            sshConfig.setPassword(server.getAuth());
        } else {
            sshConfig.setPrivateKeyContent(server.getAuth());
        }
        return sshConfig;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("解析监控长整型指标失败: {}", value);
            return 0L;
        }
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0D;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            log.warn("解析监控浮点指标失败: {}", value);
            return 0D;
        }
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("解析监控整型指标失败: {}", value);
            return 0;
        }
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private record PeriodTraffic(long rxBytes, long txBytes) {
    }

    private record RawPeriodTraffic(long downloadBytes, long uploadBytes) {
    }

    private record MonitorTrafficAdjustment(long downloadBytes, long uploadBytes) {
    }
}
