package com.fun90.airopscat.service.ratelimit;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.scheduler.ScheduledSupport;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@ApplicationScoped
public class RateLimitService {

    private static final int MAX_CONNTRACK_MARK_ID = 65534;
    private static final int MAX_TC_CLASS_MINOR = 65534;
    private static final int RESERVED_ROOT_CLASS_MINOR = 1;
    private static final int RESERVED_DEFAULT_CLASS_MINOR = 9999;
    private static final long MAX_ACCOUNT_ID_FOR_TC_CLASS = 65532L;

    @Inject
    ServerRepository serverRepository;

    @Inject
    NodeRepository nodeRepository;

    @Inject
    TagRepository tagRepository;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    ScheduledSupport scheduledSupport;

    @Inject
    @Named("monitorTaskExecutor")
    ExecutorService executorService;

    @Inject
    RequestContextController requestContextController;

    public boolean isEnabled() {
        return systemConfigService.getBooleanValue("airopscat.ratelimit.enabled", false);
    }

    public void initServer(Server server) {
        if (server == null) {
            return;
        }

        String nic = getNic(server);
        List<String> commands = List.of(
                "tc qdisc del dev %s root 2>/dev/null || true".formatted(nic),
                "tc qdisc add dev %s root handle 1: htb default 9999".formatted(nic),
                "tc class add dev %s parent 1: classid 1:1 htb rate 10000mbit".formatted(nic),
                "tc class add dev %s parent 1:1 classid 1:9999 htb rate 10000mbit".formatted(nic),
                "iptables -t mangle -C PREROUTING -j CONNMARK --restore-mark 2>/dev/null || iptables -t mangle -I PREROUTING -j CONNMARK --restore-mark",
                "iptables -t mangle -C OUTPUT -j CONNMARK --restore-mark 2>/dev/null || iptables -t mangle -I OUTPUT -j CONNMARK --restore-mark"
        );

        withConnection(server, connection -> {
            for (String command : commands) {
                executeCommand(connection, command, server.getId(), true);
            }
        });
    }

    public void applyAccountRateLimit(Server server, Account account) {
        if (!isEnabled() || server == null || account == null || account.getId() == null) {
            return;
        }
        if (account.getId() > MAX_CONNTRACK_MARK_ID) {
            log.warn("账号 ID 超出 conntrack mark 范围，跳过限速, accountId={}", account.getId());
            return;
        }
        Integer classMinor = resolveTcClassMinor(account.getId());
        if (classMinor == null) {
            log.warn("账号 ID 无法映射为有效 tc classid，跳过限速, accountId={}", account.getId());
            return;
        }

        String nic = getNic(server);
        int markId = account.getId().intValue();
        int classIdMinor = classMinor;
        int speedKbps = account.getSpeed() != null ? Math.max(account.getSpeed(), 0) * 8 : 0;

        withConnection(server, connection -> {
            if (speedKbps > 0 && (account.getDisabled() == null || account.getDisabled() == 0)) {
                String classCommand = "tc class change dev %s parent 1:1 classid 1:%d htb rate %dkbit burst 32k 2>/dev/null || tc class add dev %s parent 1:1 classid 1:%d htb rate %dkbit burst 32k"
                        .formatted(nic, classIdMinor, speedKbps, nic, classIdMinor, speedKbps);
                String filterCommand = "tc filter add dev %s parent 1: handle %d fw flowid 1:%d 2>/dev/null || true"
                        .formatted(nic, markId, classIdMinor);
                executeCommand(connection, classCommand, server.getId(), false);
                executeCommand(connection, filterCommand, server.getId(), false);
                return;
            }

            String deleteFilterCommand = "tc filter del dev %s parent 1: handle %d fw 2>/dev/null || true"
                    .formatted(nic, markId);
            String deleteClassCommand = "tc class del dev %s parent 1:1 classid 1:%d 2>/dev/null || true"
                    .formatted(nic, classIdMinor);
            executeCommand(connection, deleteFilterCommand, server.getId(), false);
            executeCommand(connection, deleteClassCommand, server.getId(), false);
        });
    }

    public void syncServer(Server server) {
        if (server == null) {
            return;
        }
        initServer(server);

        List<Node> directNodes = nodeRepository.findByServerId(server.getId());
        Set<String> nodeGroups = directNodes.stream()
                .map(Node::getNodeGroup)
                .filter(Objects::nonNull)
                .filter(group -> !group.isBlank())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<Node> groupNodes = nodeGroups.isEmpty()
                ? List.of()
                : nodeRepository.findByNodeGroupIn(new ArrayList<>(nodeGroups));

        Set<Long> nodeIds = new LinkedHashSet<>();
        directNodes.forEach(node -> nodeIds.add(node.getId()));
        groupNodes.forEach(node -> nodeIds.add(node.getId()));
        if (nodeIds.isEmpty()) {
            return;
        }

        Map<Long, List<Long>> nodeTagIdsMap = tagRepository.findTagIdsByNodeIds(new ArrayList<>(nodeIds));
        List<Long> allTagIds = nodeTagIdsMap.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();
        if (allTagIds.isEmpty()) {
            return;
        }

        Map<Long, Account> accountMap = new LinkedHashMap<>();
        for (Account account : tagRepository.findActiveAccountsByTagIds(allTagIds, LocalDateTime.now())) {
            if (account.getId() != null) {
                accountMap.put(account.getId(), account);
            }
        }

        for (Account account : accountMap.values()) {
            if (account.getSpeed() != null && account.getSpeed() > 0) {
                applyAccountRateLimit(server, account);
            }
        }
    }

    public void syncAll() {
        if (!isEnabled()) {
            log.info("全局限速开关已关闭，跳过限速规则同步");
            return;
        }

        List<Server> servers = serverRepository.findMonitorableServers(LocalDate.now());
        CompletableFuture<?>[] futures = servers.stream()
                .map(server -> CompletableFuture.runAsync(() -> {
                    runWithRequestContext(() -> {
                        try {
                            syncServer(server);
                        } catch (Exception e) {
                            log.error("同步服务器限速规则失败, serverId={}", server.getId(), e);
                        }
                    });
                }, executorService))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }

    public String getNic(Server server) {
        String nic = server == null ? null : server.getNic();
        return nic != null && !nic.isBlank() ? nic.trim() : "eth0";
    }

    private void withConnection(Server server, ConnectionConsumer consumer) {
        try (SshConnection connection = sshConnectionService.createConnection(scheduledSupport.buildSshConfig(server))) {
            consumer.accept(connection);
        } catch (Exception e) {
            log.error("执行远端限速命令失败, serverId={}", server.getId(), e);
        }
    }

    private void executeCommand(SshConnection connection, String command, Long serverId, boolean ignoreFailure) throws IOException {
        log.info("执行限速命令, serverId={}, command={}", serverId, command);
        var result = connection.executeCommand(command);
        if (!result.isSuccess() && !ignoreFailure) {
            log.error("限速命令执行失败, serverId={}, stderr={}", serverId, result.getStderr());
        } else if (!result.isSuccess()) {
            log.error("限速初始化命令执行失败, serverId={}, stderr={}", serverId, result.getStderr());
        }
    }

    private void runWithRequestContext(Runnable task) {
        boolean activated = requestContextController.activate();
        try {
            task.run();
        } finally {
            if (activated) {
                requestContextController.deactivate();
            }
        }
    }

    Integer resolveTcClassMinor(Long accountId) {
        if (accountId == null || accountId < 1 || accountId > MAX_ACCOUNT_ID_FOR_TC_CLASS) {
            return null;
        }

        int classMinor = Math.toIntExact(accountId + 1);
        if (classMinor >= RESERVED_DEFAULT_CLASS_MINOR) {
            classMinor++;
        }

        if (classMinor == RESERVED_ROOT_CLASS_MINOR || classMinor == RESERVED_DEFAULT_CLASS_MINOR || classMinor > MAX_TC_CLASS_MINOR) {
            return null;
        }
        return classMinor;
    }

    @FunctionalInterface
    private interface ConnectionConsumer {
        void accept(SshConnection connection) throws Exception;
    }
}
