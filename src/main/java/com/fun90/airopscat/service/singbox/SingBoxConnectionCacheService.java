package com.fun90.airopscat.service.singbox;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.scheduler.ScheduledSupport;
import com.fun90.airopscat.scheduler.TrafficStatsTask;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnectionsResponse;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class SingBoxConnectionCacheService {

    private final ConcurrentHashMap<Long, ClashConnectionsResponse> connectionCache = new ConcurrentHashMap<>();

    @Inject
    ServerRepository serverRepository;

    @Inject
    ServerConfigRepository serverConfigRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    SingBoxClashApiClient clashApiClient;

    @Inject
    TrafficStatsTask trafficStatsTask;

    @Inject
    ScheduledSupport scheduledSupport;

    @Inject
    @Named("monitorTaskExecutor")
    ExecutorService executorService;

    public void refreshAll() {
        LocalDate today = LocalDate.now();
        List<Server> candidateServers = serverRepository.findMonitorableServers(today);
        if (candidateServers.isEmpty()) {
            log.debug("没有可采集连接的服务器");
            triggerTrafficStats();
            return;
        }

        Set<Long> enabledSingBoxServerIds = serverConfigRepository.findAll().list().stream()
                .filter(this::isEnabledSingBoxConfig)
                .map(ServerConfig::getServerId)
                .collect(Collectors.toSet());

        List<Server> servers = candidateServers.stream()
                .filter(server -> !scheduledSupport.isInvalidServer(server, today))
                .filter(server -> enabledSingBoxServerIds.contains(server.getId()))
                .toList();

        CompletableFuture<?>[] futures = servers.stream()
                .map(server -> CompletableFuture.runAsync(() -> refreshSingleServer(server), executorService))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();
        triggerTrafficStats();
    }

    public Optional<ClashConnectionsResponse> get(Long serverId) {
        return Optional.ofNullable(connectionCache.get(serverId));
    }

    public Map<Long, ClashConnectionsResponse> getAll() {
        return Collections.unmodifiableMap(connectionCache);
    }

    private void refreshSingleServer(Server server) {
        try (SshConnection connection = sshConnectionService.createConnection(scheduledSupport.buildSshConfig(server))) {
            ClashConnectionsResponse response = clashApiClient.queryConnections(connection);
            connectionCache.put(server.getId(), response);
            int connectionCount = response == null || response.connections() == null ? 0 : response.connections().size();
            log.debug("刷新 sing-box 连接缓存完成, serverId={}, count={}", server.getId(), connectionCount);
        } catch (Exception e) {
            log.warn("刷新 sing-box 连接缓存失败，保留旧缓存, serverId={}", server.getId(), e);
        }
    }

    private void triggerTrafficStats() {
        try {
            trafficStatsTask.collectForAllServers();
        } catch (Exception e) {
            log.error("采集连接后触发流量统计失败", e);
        }
    }

    private boolean isEnabledSingBoxConfig(ServerConfig serverConfig) {
        if (serverConfig == null || serverConfig.getServerId() == null) {
            return false;
        }
        if (serverConfig.getEnabled() != null && serverConfig.getEnabled() == 0) {
            return false;
        }
        String configType = normalizeConfigType(serverConfig.getConfigType());
        return "sing-box".equals(configType) || "singbox".equals(configType);
    }

    private String normalizeConfigType(String configType) {
        if (configType == null || configType.isBlank()) {
            return null;
        }
        return configType.trim().toLowerCase();
    }
}
