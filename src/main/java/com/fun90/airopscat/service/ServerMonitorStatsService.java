package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.ServerMonitorChartDto;
import com.fun90.airopscat.model.dto.ServerMonitorPointDto;
import com.fun90.airopscat.model.dto.ServerMonitorSummaryDto;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerMonitorStats;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.ServerMonitorStatsRepository;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

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
        stats.setNetworkRxRateBytes(parseLong(metrics.get("networkRxRateBytes")));
        stats.setNetworkTxRateBytes(parseLong(metrics.get("networkTxRateBytes")));
        stats.setSampleTime(now);
        serverMonitorStatsRepository.persist(stats);

        return toSummaryDto(server, stats);
    }

    @Transactional
    public ServerMonitorSummaryDto getLatestSummary(Server server) {
        Integer cpuCores = resolveCpuCores(server);
        ServerMonitorStats latest = serverMonitorStatsRepository.findLatestByServerId(server.getId());
        if (latest == null) {
            ServerMonitorSummaryDto dto = new ServerMonitorSummaryDto();
            dto.setServerId(server.getId());
            dto.setServerName(server.getName());
            dto.setServerIp(server.getIp());
            dto.setServerHost(serverHostService.resolvePrimaryHost(server));
            dto.setCpuCores(cpuCores);
            dto.setDataAvailable(false);
            return dto;
        }
        ServerMonitorSummaryDto dto = toSummaryDto(server, latest);
        dto.setCpuCores(cpuCores);
        return dto;
    }

    public ServerMonitorChartDto getChartData(Server server, int hours) {
        int safeHours = Math.clamp(hours, 1, 24 * 7);
        LocalDateTime startTime = LocalDateTime.now().minusHours(safeHours);
        List<ServerMonitorStats> statsList = serverMonitorStatsRepository.findByServerIdAndSampleTimeAfter(server.getId(), startTime);

        ServerMonitorChartDto dto = new ServerMonitorChartDto();
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setServerIp(server.getIp());
        dto.setServerHost(serverHostService.resolvePrimaryHost(server));
        dto.setHours(safeHours);
        dto.setPoints(statsList.stream().map(this::toPointDto).toList());
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

    private ServerMonitorSummaryDto toSummaryDto(Server server, ServerMonitorStats stats) {
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
        dto.setNetworkRxBytes(stats.getNetworkRxBytes());
        dto.setNetworkTxBytes(stats.getNetworkTxBytes());
        dto.setNetworkRxRateBytes(stats.getNetworkRxRateBytes());
        dto.setNetworkTxRateBytes(stats.getNetworkTxRateBytes());
        dto.setDataAvailable(true);
        return dto;
    }

    private ServerMonitorPointDto toPointDto(ServerMonitorStats stats) {
        ServerMonitorPointDto dto = new ServerMonitorPointDto();
        dto.setSampleTime(stats.getSampleTime());
        dto.setCpuUsage(stats.getCpuUsage());
        dto.setMemoryUsage(stats.getMemoryUsage());
        dto.setMemoryUsedBytes(stats.getMemoryUsedBytes());
        dto.setMemoryTotalBytes(stats.getMemoryTotalBytes());
        dto.setNetworkRxBytes(stats.getNetworkRxBytes());
        dto.setNetworkTxBytes(stats.getNetworkTxBytes());
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
}
