package com.fun90.airopscat.service.traffic.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.service.singbox.ServerConnectionSnapshot;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnection;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnectionsResponse;
import com.fun90.airopscat.service.singbox.SingBoxConnectionCacheService;
import com.fun90.airopscat.service.singbox.SingBoxConnectionResolver;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.traffic.TrafficStatsCollector;
import com.fun90.airopscat.service.traffic.UserTrafficStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@SupportedCores(value = {"sing-box", "singbox"}, priority = 1, description = "Sing-box Clash API 流量统计采集策略")
public class SingBoxClashApiTrafficStatsCollector implements TrafficStatsCollector {

    @Inject
    AccountRepository accountRepository;

    @Inject
    SingBoxConnectionCacheService connectionCacheService;

    @Inject
    SingBoxConnectionResolver connectionResolver;

    /**
     * 每台服务器的采集快照：上次采集时间 + 各连接流量基线
     * key: serverId
     */
    private final ConcurrentHashMap<Long, ServerConnectionSnapshot> serverSnapshots = new ConcurrentHashMap<>();

    @Override
    public Map<String, UserTrafficStats> collectUserTrafficStats(SshConnection connection, Server server, ServerConfig serverConfig) {
        try {
            Optional<ClashConnectionsResponse> responseOptional = connectionCacheService.get(server.getId());
            if (responseOptional.isEmpty()) {
                log.debug("sing-box 连接缓存尚未就绪, serverId={}", server.getId());
                return Collections.emptyMap();
            }

            ClashConnectionsResponse response = responseOptional.get();
            if (response == null || response.connections() == null || response.connections().isEmpty()) {
                log.debug("clash API 没有活跃连接, serverId={}", server.getId());
                serverSnapshots.remove(server.getId());
                return Collections.emptyMap();
            }

            Instant pollTime = Instant.now();
            ServerConnectionSnapshot prev = serverSnapshots.get(server.getId());

            Map<String, long[]> trafficDelta = computeTrafficDelta(server.getId(), prev, response.connections());

            // 无论本轮是否有增量，都更新快照以便下次轮询计算增量
            serverSnapshots.put(server.getId(), buildSnapshot(pollTime, response.connections()));

            if (prev == null) {
                // 首次轮询：仅建立基线，本轮不计费，避免长连接全量重复计费
                log.debug("serverId={} 首次采集，建立基线，本轮不计费", server.getId());
                return Collections.emptyMap();
            }

            if (trafficDelta.isEmpty()) {
                return Collections.emptyMap();
            }

            return toUserTrafficStats(trafficDelta);

        } catch (Exception e) {
            log.error("获取 sing-box clash API 流量统计失败, serverId={}", server.getId(), e);
            return Collections.emptyMap();
        }
    }

    @Override
    public String getStrategyName() {
        return "sing-box";
    }

    /**
     * 计算每个 inbound tag 的增量流量，处理 AirOpsCat 和 sing-box 重启场景：
     * <ul>
     *   <li>连接 start > lastPollTime：上次采集后才建立的新连接，全量计费</li>
     *   <li>连接 start ≤ lastPollTime 且有快照：计算增量</li>
     *   <li>连接 start ≤ lastPollTime 且无快照（重启后第一次看到）：跳过，避免重复计费</li>
     * </ul>
     */
    private Map<String, long[]> computeTrafficDelta(Long serverId, ServerConnectionSnapshot prev, List<ClashConnection> connections) {
        Map<String, long[]> accountTrafficDelta = new LinkedHashMap<>();
        Map<String, Account> exactAccountMap = buildExactTrafficAccountMap(connections);

        for (ClashConnection conn : connections) {
            if (conn.id() == null || conn.metadata() == null) {
                continue;
            }

            long upload = conn.upload() != null ? conn.upload() : 0L;
            long download = conn.download() != null ? conn.download() : 0L;

            long deltaUpload;
            long deltaDownload;

            if (prev == null) {
                // 首次轮询，由外层直接返回空，不走到这里
                continue;
            }

            Instant connStart = parseConnStart(conn.start());
            if (connStart != null && connStart.isAfter(prev.lastPollTime())) {
                // 上次采集后才建立的新连接，全量计费
                deltaUpload = upload;
                deltaDownload = download;
            } else {
                // 已存在的连接，尝试计算增量
                long[] baseline = prev.connections().get(conn.id());
                if (baseline == null) {
                    // 无快照记录（AirOpsCat 或 sing-box 重启后残留的不明连接），跳过
                    log.debug("连接 {} 无基线记录且 start 时间不晚于上次采集，跳过", conn.id());
                    continue;
                }
                deltaUpload = Math.max(0L, upload - baseline[0]);
                deltaDownload = Math.max(0L, download - baseline[1]);
            }

            if (deltaUpload > 0 || deltaDownload > 0) {
                Account exactAccount = connectionResolver.resolveExactAccount(conn, exactAccountMap).orElse(null);
                if (exactAccount != null) {
                    accountTrafficDelta.merge(exactAccount.getAccountNo(), new long[]{deltaUpload, deltaDownload},
                            (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
                }
                else {
                    String authUser = connectionResolver.extractAuthUser(conn);
                    if (authUser != null) {
                        log.debug("serverId={} 未找到 authUser 对应的活跃账号，跳过精确记账, authUser={}", serverId, authUser);
                    }
                }
            }
        }

        return accountTrafficDelta;
    }

    private ServerConnectionSnapshot buildSnapshot(Instant pollTime, List<ClashConnection> connections) {
        Map<String, long[]> snapshot = new HashMap<>();
        for (ClashConnection conn : connections) {
            if (conn.id() == null) {
                continue;
            }
            long upload = conn.upload() != null ? conn.upload() : 0L;
            long download = conn.download() != null ? conn.download() : 0L;
            snapshot.put(conn.id(), new long[]{upload, download});
        }
        return new ServerConnectionSnapshot(pollTime, snapshot);
    }

    private Map<String, Account> buildExactTrafficAccountMap(List<ClashConnection> connections) {
        Set<String> authUsers = connections.stream()
                .map(connectionResolver::extractAuthUser)
                .filter(authUser -> authUser != null && !authUser.isBlank())
                .collect(Collectors.toSet());
        if (authUsers.isEmpty()) {
            return Map.of();
        }

        return accountRepository.findByAccountNos(authUsers).stream()
                .filter(Account::isActive)
                .collect(Collectors.toMap(Account::getAccountNo, account -> account, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, UserTrafficStats> toUserTrafficStats(Map<String, long[]> accountTrafficMap) {
        Map<String, UserTrafficStats> result = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : accountTrafficMap.entrySet()) {
            result.put(entry.getKey(), new UserTrafficStats(entry.getValue()[0], entry.getValue()[1]));
        }
        return result;
    }

    private Instant parseConnStart(String start) {
        if (start == null || start.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(start).toInstant();
        } catch (Exception e) {
            log.debug("解析连接 start 时间失败: {}", start);
            return null;
        }
    }

}
