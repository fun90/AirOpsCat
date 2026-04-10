package com.fun90.airopscat.service.ratelimit;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.scheduler.ScheduledSupport;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnection;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnectionMetadata;
import com.fun90.airopscat.service.singbox.SingBoxClashApiClient.ClashConnectionsResponse;
import com.fun90.airopscat.service.singbox.SingBoxConnectionCacheService;
import com.fun90.airopscat.service.singbox.SingBoxConnectionResolver;
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
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class ConntrackMarkService {

    private final ConcurrentHashMap<Long, Set<String>> markedConnections = new ConcurrentHashMap<>();

    @Inject
    AccountRepository accountRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    SingBoxConnectionCacheService connectionCacheService;

    @Inject
    SingBoxConnectionResolver connectionResolver;

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
        Map<String, Account> exactAccountMap = buildExactRateLimitAccountMap(response.connections());

        for (ClashConnection connection : response.connections()) {
            Account account = resolveRateLimitAccount(connection, exactAccountMap);
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

    private Map<String, Account> buildExactRateLimitAccountMap(List<ClashConnection> connections) {
        Set<String> authUsers = connections.stream()
                .map(connectionResolver::extractAuthUser)
                .filter(authUser -> authUser != null && !authUser.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (authUsers.isEmpty()) {
            return Map.of();
        }
        return accountRepository.findByAccountNos(authUsers).stream()
                .filter(Account::isActive)
                .filter(account -> account.getSpeed() != null && account.getSpeed() > 0)
                .collect(Collectors.toMap(Account::getAccountNo, account -> account, (left, right) -> left, LinkedHashMap::new));
    }

    private Account resolveRateLimitAccount(ClashConnection connection, Map<String, Account> exactAccountMap) {
        return connectionResolver.resolveExactAccount(connection, exactAccountMap).orElse(null);
    }
}
