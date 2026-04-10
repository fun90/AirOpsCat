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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
     * 每台服务器的采集快照：上次采集时间 + 各连接流量基线
     * key: serverId
     */
    private final ConcurrentHashMap<Long, ServerTrafficSnapshot> serverSnapshots = new ConcurrentHashMap<>();

    @Override
    public Map<String, UserTrafficStats> collectUserTrafficStats(SshConnection connection, Server server, ServerConfig serverConfig) {
        try {
            ClashConnectionsResponse response = clashApiClient.queryConnections(connection);
            if (response == null || response.connections() == null || response.connections().isEmpty()) {
                log.debug("clash API 没有活跃连接, serverId={}", server.getId());
                serverSnapshots.remove(server.getId());
                return Collections.emptyMap();
            }

            Instant pollTime = Instant.now();
            ServerTrafficSnapshot prev = serverSnapshots.get(server.getId());

            Map<String, long[]> tagTrafficDelta = computeTagTrafficDelta(prev, response.connections());

            // 无论本轮是否有增量，都更新快照以便下次轮询计算增量
            serverSnapshots.put(server.getId(), buildSnapshot(pollTime, response.connections()));

            if (prev == null) {
                // 首次轮询：仅建立基线，本轮不计费，避免长连接全量重复计费
                log.debug("serverId={} 首次采集，建立基线，本轮不计费", server.getId());
                return Collections.emptyMap();
            }

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
     * 计算每个 inbound tag 的增量流量，处理 AirOpsCat 和 sing-box 重启场景：
     * <ul>
     *   <li>连接 start > lastPollTime：上次采集后才建立的新连接，全量计费</li>
     *   <li>连接 start ≤ lastPollTime 且有快照：计算增量</li>
     *   <li>连接 start ≤ lastPollTime 且无快照（重启后第一次看到）：跳过，避免重复计费</li>
     * </ul>
     */
    private Map<String, long[]> computeTagTrafficDelta(ServerTrafficSnapshot prev, List<ClashConnection> connections) {
        Map<String, long[]> tagTrafficDelta = new LinkedHashMap<>();

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
                tagTrafficDelta.merge(inboundTag, new long[]{deltaUpload, deltaDownload},
                        (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
            }
        }

        return tagTrafficDelta;
    }

    private ServerTrafficSnapshot buildSnapshot(Instant pollTime, List<ClashConnection> connections) {
        Map<String, long[]> snapshot = new HashMap<>();
        for (ClashConnection conn : connections) {
            if (conn.id() == null) {
                continue;
            }
            long upload = conn.upload() != null ? conn.upload() : 0L;
            long download = conn.download() != null ? conn.download() : 0L;
            snapshot.put(conn.id(), new long[]{upload, download});
        }
        return new ServerTrafficSnapshot(pollTime, snapshot);
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

    /**
     * 每台服务器的采集快照
     *
     * @param lastPollTime  本次采集时间，下次采集用于判断新旧连接
     * @param connections   本次所有活跃连接的流量基线，key=connectionId，value=[upload, download]
     */
    private record ServerTrafficSnapshot(Instant lastPollTime, Map<String, long[]> connections) {}
}
