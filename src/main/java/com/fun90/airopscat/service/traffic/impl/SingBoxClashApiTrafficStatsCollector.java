package com.fun90.airopscat.service.traffic.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnection;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnectionsResponse;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.traffic.TrafficStatsCollector;
import com.fun90.airopscat.service.traffic.UserTrafficStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@SupportedCores(value = {"sing-box", "singbox"}, priority = 1, description = "Sing-box Clash API 流量统计采集策略")
public class SingBoxClashApiTrafficStatsCollector implements TrafficStatsCollector {

    private static final String NODE_TAG_PREFIX = "node_";

    @Inject
    SingBoxClashApiClient clashApiClient;

    @Inject
    TagRepository tagRepository;

    /**
     * 记录上次采集时每个连接的流量快照，用于增量计算
     * key: serverId, value: (connectionId → [upload, download])
     */
    private final ConcurrentHashMap<Long, Map<String, long[]>> previousSnapshots = new ConcurrentHashMap<>();

    @Override
    public Map<String, UserTrafficStats> collectUserTrafficStats(SshConnection connection, Server server, ServerConfig serverConfig) {
        try {
            ClashConnectionsResponse response = clashApiClient.queryConnections(connection);
            if (response == null || response.connections() == null || response.connections().isEmpty()) {
                log.debug("clash API 没有活跃连接, serverId={}", server.getId());
                previousSnapshots.remove(server.getId());
                return Collections.emptyMap();
            }

            Map<String, long[]> tagTrafficDelta = computeTagTrafficDelta(server.getId(), response.connections());
            if (tagTrafficDelta.isEmpty()) {
                return Collections.emptyMap();
            }

            return mapTagTrafficToAccounts(server.getId(), tagTrafficDelta);

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
     * 根据当前连接列表与上次快照计算每个 inbound tag 的增量流量
     */
    private Map<String, long[]> computeTagTrafficDelta(Long serverId, List<ClashConnection> connections) {
        Map<String, long[]> currentSnapshot = new HashMap<>();
        Map<String, long[]> tagTrafficDelta = new LinkedHashMap<>();

        Map<String, long[]> prev = previousSnapshots.getOrDefault(serverId, Collections.emptyMap());

        for (ClashConnection conn : connections) {
            if (conn.id() == null || conn.metadata() == null) {
                continue;
            }
            String inboundTag = extractInboundTag(conn.metadata().type());
            if (inboundTag == null) {
                continue;
            }

            long upload = conn.upload() != null ? conn.upload() : 0L;
            long download = conn.download() != null ? conn.download() : 0L;

            currentSnapshot.put(conn.id(), new long[]{upload, download});

            long[] previous = prev.get(conn.id());
            long deltaUpload = previous != null ? Math.max(0L, upload - previous[0]) : upload;
            long deltaDownload = previous != null ? Math.max(0L, download - previous[1]) : download;

            if (deltaUpload > 0 || deltaDownload > 0) {
                tagTrafficDelta.merge(inboundTag, new long[]{deltaUpload, deltaDownload},
                        (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
            }
        }

        previousSnapshots.put(serverId, currentSnapshot);
        return tagTrafficDelta;
    }

    /**
     * 将每个 inbound tag 的增量流量映射到对应账号
     * inbound tag 格式：node_{nodeId}，通过 tag→node→account 关系查找账号
     */
    private Map<String, UserTrafficStats> mapTagTrafficToAccounts(Long serverId, Map<String, long[]> tagTrafficDelta) {
        Map<Long, long[]> nodeIdTrafficMap = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : tagTrafficDelta.entrySet()) {
            Long nodeId = extractNodeId(entry.getKey());
            if (nodeId == null) {
                log.debug("无法从 inbound tag 解析 nodeId: {}", entry.getKey());
                continue;
            }
            nodeIdTrafficMap.put(nodeId, entry.getValue());
        }

        if (nodeIdTrafficMap.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> nodeIds = new ArrayList<>(nodeIdTrafficMap.keySet());
        Map<Long, List<Long>> nodeTagIdsMap = tagRepository.findTagIdsByNodeIds(nodeIds);

        List<Long> allTagIds = nodeTagIdsMap.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();

        if (allTagIds.isEmpty()) {
            log.debug("serverId={} 的节点没有关联的 tag，跳过流量统计", serverId);
            return Collections.emptyMap();
        }

        Map<Long, List<Long>> tagAccountIdsMap = tagRepository.findAccountIdsByTagIds(allTagIds);
        List<Long> allAccountIds = tagAccountIdsMap.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();

        if (allAccountIds.isEmpty()) {
            return Collections.emptyMap();
        }

        LocalDateTime now = LocalDateTime.now();
        List<Account> activeAccounts = tagRepository.findActiveAccountsByTagIds(allTagIds, now);
        Map<Long, Account> accountById = activeAccounts.stream()
                .collect(Collectors.toMap(Account::getId, a -> a, (a, b) -> a));

        Map<Long, List<String>> nodeAccountNosMap = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Long>> entry : nodeTagIdsMap.entrySet()) {
            Long nodeId = entry.getKey();
            for (Long tagId : entry.getValue()) {
                for (Long accountId : tagAccountIdsMap.getOrDefault(tagId, Collections.emptyList())) {
                    Account account = accountById.get(accountId);
                    if (account != null) {
                        nodeAccountNosMap.computeIfAbsent(nodeId, k -> new ArrayList<>())
                                .add(account.getAccountNo());
                    }
                }
            }
        }

        Map<String, long[]> accountTrafficMap = new LinkedHashMap<>();
        for (Map.Entry<Long, long[]> entry : nodeIdTrafficMap.entrySet()) {
            Long nodeId = entry.getKey();
            long[] traffic = entry.getValue();
            List<String> accountNos = nodeAccountNosMap.getOrDefault(nodeId, Collections.emptyList());
            if (accountNos.isEmpty()) {
                log.debug("节点 {} 没有关联的活跃账号，跳过流量统计", nodeId);
                continue;
            }
            for (String accountNo : accountNos) {
                accountTrafficMap.merge(accountNo, new long[]{traffic[0], traffic[1]},
                        (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
            }
        }

        Map<String, UserTrafficStats> result = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : accountTrafficMap.entrySet()) {
            result.put(entry.getKey(), new UserTrafficStats(entry.getValue()[0], entry.getValue()[1]));
        }
        return result;
    }

    /**
     * 从 metadata.type（如 "vless/node_11"）中提取 inbound tag（"node_11"）
     */
    private String extractInboundTag(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        int slashIndex = type.indexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        String tag = type.substring(slashIndex + 1).trim();
        return tag.isBlank() ? null : tag;
    }

    /**
     * 从 inbound tag（如 "node_11"）中提取 nodeId（11L）
     */
    private Long extractNodeId(String tag) {
        if (tag == null || !tag.startsWith(NODE_TAG_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(tag.substring(NODE_TAG_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
