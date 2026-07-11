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
 * 账户防共享跨节点聚合器（合并请求方案的核心）。
 *
 * <p>维护一张「每账户 → 每节点最近上报」的内存表，收到某节点的 guard-sync 上报时：
 * <ol>
 *   <li>用上报覆盖 {@code 表[各accountNo][nodeIp]} 这一格，刷新时间戳；</li>
 *   <li>只对本次上报涉及的账户实时求和：累加所有节点的连接数、合并所有节点的 IP
 *       集合去重，得到全局 totalConnections / totalIps；</li>
 *   <li>与 {@link Account#getMaxConnections()} / {@link Account#getMaxIps()} 比较，
 *       超限则计入本次响应的黑名单。</li>
 * </ol>
 *
 * <p><b>每格必须带 TTL</b>：某节点宕机或网络中断不再上报时，其旧数据不能永远累加进
 * 总量，否则会把已下线节点的连接长期算入导致误判。求和时跳过超 TTL 的过期格，另有
 * 低频清理任务剔除僵尸格（见 {@link #evictStaleNodes()}）。
 *
 * <p>内存表不落库：5 秒级高频，落库无必要；中心重启后由各 agent 在数个周期内重新
 * 上报重建。
 */
@Slf4j
@ApplicationScoped
public class AccountGuardAggregator {

    /** 单节点上报的一格数据。 */
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

    /** accountNo -> (nodeIp -> NodeStat)。外层与内层都用并发容器保证多节点并发上报安全。 */
    private final Map<String, Map<String, NodeStat>> table = new ConcurrentHashMap<>();

    private final AccountRepository accountRepository;
    private final SystemConfigService systemConfigService;

    @Inject
    public AccountGuardAggregator(AccountRepository accountRepository,
                                  SystemConfigService systemConfigService) {
        this.accountRepository = accountRepository;
        this.systemConfigService = systemConfigService;
    }

    /**
     * 处理一次 guard-sync 上报：更新该节点的格，求和涉及账户，返回被阻断账户黑名单。
     *
     * @return accountNo -> 阻断明细；仅包含本次上报涉及且超总限的账户。
     */
    public Map<String, GuardBlockedEntry> reportAndEvaluate(GuardSyncRequest request) {
        String nodeIp = request.getNodeIp();
        long now = nowEpochSeconds();
        List<GuardSyncAccountReport> reports = request.getAccounts() == null
                ? List.of() : request.getAccounts();

        // 1) 更新本节点在各账户下的格。本次上报未提及的账户，需要清掉该节点的旧格
        //    （表示该账户在此节点已无连接），避免旧数据滞留导致高估。
        Set<String> reportedAccountNos = new HashSet<>();
        for (GuardSyncAccountReport report : reports) {
            String accountNo = report.getAccountNo();
            if (accountNo == null || accountNo.isBlank()) {
                continue;
            }
            reportedAccountNos.add(accountNo);
            Set<String> ips = report.getIps() == null ? Set.of() : new HashSet<>(report.getIps());
            int connections = Math.max(report.getConnections(), 0);
            table.computeIfAbsent(accountNo, k -> new ConcurrentHashMap<>())
                    .put(nodeIp, new NodeStat(connections, ips, now));
        }
        // 清除该节点在「本次未上报的账户」下的残留格
        for (Map.Entry<String, Map<String, NodeStat>> entry : table.entrySet()) {
            if (!reportedAccountNos.contains(entry.getKey())) {
                entry.getValue().remove(nodeIp);
            }
        }

        if (reportedAccountNos.isEmpty()) {
            return Map.of();
        }

        // 2) 加载涉及账户的限制值
        Map<String, Account> accountMap = loadAccounts(reportedAccountNos);

        // 3) 求和 + 判定
        int ttl = getTtlSeconds();
        Map<String, GuardBlockedEntry> blocked = new HashMap<>();
        for (String accountNo : reportedAccountNos) {
            Account account = accountMap.get(accountNo);
            if (account == null) {
                continue;
            }
            GuardBlockedEntry entry = evaluateAccount(accountNo, account, now, ttl);
            if (entry != null) {
                blocked.put(accountNo, entry);
            }
        }
        return blocked;
    }

    /** 对单个账户跨节点求和并判定是否超总限；未超返回 null。 */
    private GuardBlockedEntry evaluateAccount(String accountNo, Account account, long now, int ttl) {
        Map<String, NodeStat> byNode = table.get(accountNo);
        if (byNode == null || byNode.isEmpty()) {
            return null;
        }

        int totalConnections = 0;
        Set<String> totalIps = new HashSet<>();
        for (NodeStat stat : byNode.values()) {
            // 跳过超 TTL 的过期格（宕机 / 断连节点的旧数据不计入）
            if (now - stat.reportedAtEpochSeconds > ttl) {
                continue;
            }
            totalConnections += stat.connections;
            totalIps.addAll(stat.ips);
        }

        // 连接数维度
        Integer maxConnections = account.getMaxConnections();
        if (maxConnections != null && maxConnections > 0 && totalConnections > maxConnections) {
            return new GuardBlockedEntry("connections", totalConnections, maxConnections);
        }
        // IP 数维度（防共享主判据）
        Integer maxIps = account.getMaxIps();
        if (maxIps != null && maxIps > 0 && totalIps.size() > maxIps) {
            return new GuardBlockedEntry("ips", totalIps.size(), maxIps);
        }
        return null;
    }

    /**
     * 低频清理僵尸节点格：移除超 TTL 未上报的格，空账户条目一并删除。
     * 由定时任务调用（不在 guard-sync 热路径）。
     */
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
            log.debug("guard 聚合表清理：移除空账户条目 {} 个", removed);
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

    /** 配额表 TTL（秒），与响应中下发给 agent 的 ttlSeconds 一致。 */
    public int getTtlSeconds() {
        return Math.max(5, systemConfigService.getIntValue("airopscat.account.guard.ttl-seconds", 15));
    }

    private long nowEpochSeconds() {
        return java.time.Instant.now().getEpochSecond();
    }

    /** 仅供测试/诊断：当前聚合表中的账户数。 */
    int trackedAccountCount() {
        return table.size();
    }
}
