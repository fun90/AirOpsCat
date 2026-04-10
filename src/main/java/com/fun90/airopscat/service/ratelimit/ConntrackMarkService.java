package com.fun90.airopscat.service.ratelimit;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.scheduler.ScheduledSupport;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnection;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnectionMetadata;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnectionsResponse;
import com.fun90.airopscat.service.singbox.SingBoxConnectionCacheService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@ApplicationScoped
public class ConntrackMarkService {

    private static final Pattern NODE_ID_PATTERN = Pattern.compile("/node_(\\d+)");

    private final ConcurrentHashMap<Long, Set<String>> markedConnections = new ConcurrentHashMap<>();

    @Inject
    TagRepository tagRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    SingBoxConnectionCacheService connectionCacheService;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    ScheduledSupport scheduledSupport;

    public void markAll() {
        if (!systemConfigService.getBooleanValue("airopscat.ratelimit.enabled", false)) {
            return;
        }

        for (Map.Entry<Long, ClashConnectionsResponse> entry : connectionCacheService.getAll().entrySet()) {
            try {
                markServerConnections(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.error("执行 conntrack 打标失败, serverId={}", entry.getKey(), e);
            }
        }
    }

    private void markServerConnections(Long serverId, ClashConnectionsResponse response) {
        Server server = serverRepository.findById(serverId);
        if (scheduledSupport.isInvalidServer(server, LocalDate.now())) {
            return;
        }
        if (response == null || response.connections() == null || response.connections().isEmpty()) {
            markedConnections.remove(serverId);
            return;
        }

        Set<String> currentKeys = new LinkedHashSet<>();
        Set<String> cachedMarkedKeys = markedConnections.computeIfAbsent(serverId, ignored -> ConcurrentHashMap.newKeySet());
        List<String> commands = new ArrayList<>();

        for (ClashConnection connection : response.connections()) {
            Account account = resolveSingleAccount(connection);
            if (account == null || account.getId() == null || account.getId() > 65534) {
                continue;
            }

            ClashConnectionMetadata metadata = connection.metadata();
            if (metadata == null || metadata.sourceIP() == null || metadata.sourcePort() == null) {
                continue;
            }

            String key = metadata.sourceIP() + ":" + metadata.sourcePort();
            currentKeys.add(key);
            if (cachedMarkedKeys.contains(key)) {
                continue;
            }

            String protocol = "tcp".equalsIgnoreCase(metadata.network()) ? "tcp" : "udp";
            commands.add("conntrack -U -p %s --src %s --sport %s --mark %d"
                    .formatted(protocol, metadata.sourceIP(), metadata.sourcePort(), account.getId()));
        }

        if (!commands.isEmpty()) {
            try (SshConnection sshConnection = sshConnectionService.createConnection(scheduledSupport.buildSshConfig(server))) {
                var result = sshConnection.executeCommand(String.join("\n", commands));
                if (result.isSuccess()) {
                    cachedMarkedKeys.addAll(currentKeys);
                } else {
                    log.warn("conntrack 批量打标失败, serverId={}, stderr={}", serverId, result.getStderr());
                }
            } catch (Exception e) {
                log.error("执行 conntrack 批量打标失败, serverId={}", serverId, e);
            }
        }

        cachedMarkedKeys.retainAll(currentKeys);
    }

    private Account resolveSingleAccount(ClashConnection connection) {
        Long nodeId = extractNodeId(connection);
        if (nodeId == null) {
            return null;
        }

        Map<Long, List<Long>> nodeTagIdsMap = tagRepository.findTagIdsByNodeIds(List.of(nodeId));
        List<Long> tagIds = nodeTagIdsMap.get(nodeId);
        if (tagIds == null || tagIds.isEmpty()) {
            return null;
        }

        List<Account> accounts = tagRepository.findActiveAccountsByTagIds(tagIds, LocalDateTime.now()).stream()
                .filter(account -> account.getSpeed() != null && account.getSpeed() > 0)
                .toList();
        if (accounts.size() != 1) {
            log.debug("节点 {} 对应账号数量不是 1，跳过 conntrack 打标, count={}", nodeId, accounts.size());
            return null;
        }
        return accounts.getFirst();
    }

    private Long extractNodeId(ClashConnection connection) {
        if (connection == null || connection.metadata() == null || connection.metadata().type() == null) {
            return null;
        }
        Matcher matcher = NODE_ID_PATTERN.matcher(connection.metadata().type());
        if (!matcher.find()) {
            return null;
        }
        return Long.parseLong(matcher.group(1));
    }
}
