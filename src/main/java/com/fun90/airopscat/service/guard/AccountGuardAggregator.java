package com.fun90.airopscat.service.guard;

import com.fun90.airopscat.model.dto.guard.GuardBlockedEntry;
import com.fun90.airopscat.model.dto.guard.GuardSyncAccountReport;
import com.fun90.airopscat.model.dto.guard.GuardSyncRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.service.SystemConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账户防共享跨节点聚合器。
 *
 * <p>收到节点 guard-sync 上报时，维护 accountNo -> nodeIp -> NodeStat 的内存表，
 * 并按 TTL 跳过过期节点格，实时汇总总连接数与去重 IP 数。</p>
 */
@Slf4j
@ApplicationScoped
public class AccountGuardAggregator {

    private static final class NodeStat {
        final int connections;
        final Set<String> ips;
        final long reportedAtEpochSeconds;

        NodeStat(int connections, Set<String> ips, long reportedAtEpochSeconds) {
            this.connections = connections;
            this.ips = ips;
            this.reportedAtEpochSeconds = reportedAtEpochSeconds;
        }
    }

    private final Map<String, Map<String, NodeStat>> table = new ConcurrentHashMap<>();

    private final AccountRepository accountRepository;
    private final SystemConfigService systemConfigService;

    @Inject
    public AccountGuardAggregator(AccountRepository accountRepository,
                                  SystemConfigService systemConfigService) {
        this.accountRepository = accountRepository;
        this.systemConfigService = systemConfigService;
    }

    public Map<String, GuardBlockedEntry> reportAndEvaluate(GuardSyncRequest request) {
        return reportAndEvaluateWithStats(request).getBlockedAccounts();
    }

    public AccountGuardEvaluation reportAndEvaluateWithStats(GuardSyncRequest request) {
        String nodeIp = request.getNodeIp();
        long now = nowEpochSeconds();
        List<GuardSyncAccountReport> reports = request.getAccounts() == null
                ? List.of() : request.getAccounts();

        Set<String> reportedAccountNos = new HashSet<>();
        Set<String> affectedAccountNos = new HashSet<>();
        for (GuardSyncAccountReport report : reports) {
            String accountNo = report.getAccountNo();
            if (accountNo == null || accountNo.isBlank()) {
                continue;
            }
            reportedAccountNos.add(accountNo);
            affectedAccountNos.add(accountNo);

            Set<String> ips = report.getIps() == null ? Set.of() : new HashSet<>(report.getIps());
            int connections = Math.max(report.getConnections(), 0);
            table.computeIfAbsent(accountNo, key -> new ConcurrentHashMap<>())
                    .put(nodeIp, new NodeStat(connections, ips, now));
        }

        // 本节点这次未上报的账号，表示该账号在本节点已经没有连接，需要移除旧格。
        for (Map.Entry<String, Map<String, NodeStat>> entry : table.entrySet()) {
            if (!reportedAccountNos.contains(entry.getKey())) {
                NodeStat removed = entry.getValue().remove(nodeIp);
                if (removed != null) {
                    affectedAccountNos.add(entry.getKey());
                }
            }
        }

        if (affectedAccountNos.isEmpty()) {
            return new AccountGuardEvaluation(Map.of(), Map.of());
        }

        Map<String, Account> accountMap = loadAccounts(affectedAccountNos);
        int ttl = getTtlSeconds();
        Map<String, GuardBlockedEntry> blocked = new HashMap<>();
        Map<String, AccountGuardStats> statsByAccountNo = new HashMap<>();

        for (String accountNo : affectedAccountNos) {
            Account account = accountMap.get(accountNo);
            if (account == null) {
                continue;
            }
            AccountGuardStats stats = calculateStats(accountNo, now, ttl);
            statsByAccountNo.put(accountNo, stats);

            // guard 响应仍只返回本次上报涉及账号的阻断结论。
            if (!reportedAccountNos.contains(accountNo)) {
                continue;
            }
            GuardBlockedEntry entry = evaluateAccount(account, stats);
            if (entry != null) {
                blocked.put(accountNo, entry);
            }
        }

        return new AccountGuardEvaluation(blocked, statsByAccountNo);
    }

    private AccountGuardStats calculateStats(String accountNo, long now, int ttl) {
        Map<String, NodeStat> byNode = table.get(accountNo);
        if (byNode == null || byNode.isEmpty()) {
            return new AccountGuardStats(accountNo, 0, 0, 0);
        }

        int totalConnections = 0;
        Set<String> totalIps = new HashSet<>();
        int activeNodeCount = 0;
        for (NodeStat stat : byNode.values()) {
            if (now - stat.reportedAtEpochSeconds > ttl) {
                continue;
            }
            activeNodeCount++;
            totalConnections += stat.connections;
            totalIps.addAll(stat.ips);
        }
        return new AccountGuardStats(accountNo, totalConnections, totalIps.size(), activeNodeCount);
    }

    private GuardBlockedEntry evaluateAccount(Account account, AccountGuardStats stats) {
        Integer maxConnections = account.getMaxConnections();
        if (maxConnections != null && maxConnections > 0 && stats.getTotalConnections() > maxConnections) {
            return new GuardBlockedEntry("connections", stats.getTotalConnections(), maxConnections);
        }

        Integer maxIps = account.getMaxIps();
        if (maxIps != null && maxIps > 0 && stats.getTotalIps() > maxIps) {
            return new GuardBlockedEntry("ips", stats.getTotalIps(), maxIps);
        }
        return null;
    }

    public void evictStaleNodes() {
        long now = nowEpochSeconds();
        int ttl = getTtlSeconds();
        int removed = 0;
        for (Map.Entry<String, Map<String, NodeStat>> entry : table.entrySet()) {
            Map<String, NodeStat> byNode = entry.getValue();
            byNode.values().removeIf(stat -> now - stat.reportedAtEpochSeconds > ttl);
            if (byNode.isEmpty()) {
                table.remove(entry.getKey(), byNode);
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("guard 聚合表清理：移除空账号条目 {} 个", removed);
        }
    }

    private Map<String, Account> loadAccounts(Set<String> accountNos) {
        Map<String, Account> map = new HashMap<>();
        List<Account> accounts = accountRepository.list("accountNo in ?1", new ArrayList<>(accountNos));
        for (Account account : accounts) {
            if (account.getAccountNo() != null) {
                map.put(account.getAccountNo(), account);
            }
        }
        return map;
    }

    public int getTtlSeconds() {
        return Math.max(5, systemConfigService.getIntValue("airopscat.account.guard.ttl-seconds", 15));
    }

    private long nowEpochSeconds() {
        return java.time.Instant.now().getEpochSecond();
    }

    int trackedAccountCount() {
        return table.size();
    }
}
