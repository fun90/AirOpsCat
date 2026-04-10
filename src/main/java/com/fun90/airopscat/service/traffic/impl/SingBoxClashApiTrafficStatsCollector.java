package com.fun90.airopscat.service.traffic.impl;

import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.traffic.TrafficStatsCollector;
import com.fun90.airopscat.service.traffic.UserTrafficStats;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@SupportedCores(value = {"sing-box", "singbox"}, priority = 1, description = "Sing-box Clash API 流量统计采集策略")
public class SingBoxClashApiTrafficStatsCollector implements TrafficStatsCollector {

    private static final String NODE_TAG_PREFIX = "node_";

    @ConfigProperty(name = "airopscat.sing-box.clash-api.port", defaultValue = "19191")
    int clashApiPort;

    @Inject
    NodeRepository nodeRepository;

    @Inject
    TagRepository tagRepository;

    @Inject
    AccountRepository accountRepository;

    /**
     * 记录上次采集时每个连接的流量快照，用于增量计算
     * key: serverId, value: (connectionId → [upload, download])
     */
    private final ConcurrentHashMap<Long, Map<String, long[]>> previousSnapshots = new ConcurrentHashMap<>();

    @Override
    public Map<String, UserTrafficStats> collectUserTrafficStats(SshConnection connection, Server server, ServerConfig serverConfig) {
        try {
            String command = String.format("curl -s http://127.0.0.1:%d/connections", clashApiPort);
            CommandResult result = connection.executeCommand(command);
            if (!result.isSuccess()) {
                log.error("执行 clash API 命令失败, serverId={}, stderr={}", server.getId(), result.getStderr());
                return Collections.emptyMap();
            }

            String output = result.getStdout();
            if (output == null || output.isBlank()) {
                log.debug("clash API 返回空结果, serverId={}", server.getId());
                return Collections.emptyMap();
            }

            ClashConnectionsResponse response = JsonUtil.toObject(output, ClashConnectionsResponse.class);
            if (response == null || response.connections() == null || response.connections().isEmpty()) {
                log.debug("clash API 没有活跃连接, serverId={}", server.getId());
                previousSnapshots.remove(server.getId());
                return Collections.emptyMap();
            }

            // 计算每个 inbound tag 的增量流量
            Map<String, long[]> tagTrafficDelta = computeTagTrafficDelta(server.getId(), response.connections());

            if (tagTrafficDelta.isEmpty()) {
                return Collections.emptyMap();
            }

            // 将 inbound tag 映射到账号
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
        // 从 inbound tag 提取 node ID
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

        // 查询 node → tag → account 映射
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

        // 构造 nodeId → accountNos 映射
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

        // 汇总每个账号的流量
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

    // DTO records for JSON deserialization

    record ClashConnectionsResponse(
            List<ClashConnection> connections,
            Long downloadTotal,
            Long uploadTotal,
            Long memory
    ) {}

    record ClashConnection(
            String id,
            Long upload,
            Long download,
            ClashConnectionMetadata metadata,
            List<String> chains,
            String rule,
            String rulePayload,
            String start
    ) {}

    record ClashConnectionMetadata(
            String type,
            String network,
            String host,
            String sourceIP,
            String sourcePort,
            String destinationIP,
            String destinationPort,
            String dnsMode,
            String processPath
    ) {}
}
