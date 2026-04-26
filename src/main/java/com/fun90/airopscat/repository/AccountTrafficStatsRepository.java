package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class AccountTrafficStatsRepository implements PanacheRepository<AccountTrafficStats> {

    public Long sumUploadBytesByAccountId(Long accountId) {
        return find("select sum(uploadBytes) from AccountTrafficStats where accountId = ?1 and periodStart <= ?2 and periodEnd > ?2", accountId, LocalDateTime.now())
                .project(Long.class)
                .firstResult();
    }

    public Long sumDownloadBytesByAccountId(Long accountId) {
        return find("select sum(downloadBytes) from AccountTrafficStats where accountId = ?1 and periodStart <= ?2 and periodEnd > ?2", accountId, LocalDateTime.now())
                .project(Long.class)
                .firstResult();
    }

    public Map<Long, Long> sumBytesByAccountIds(List<Long> accountIds, LocalDateTime currentTime) {
        Map<Long, Long> result = new HashMap<>();
        if (accountIds == null || accountIds.isEmpty()) {
            return result;
        }

        List<Object[]> rows = getEntityManager().createQuery(
                "select ats.accountId, sum(ats.downloadBytes) + sum(ats.uploadBytes) " +
                        "from AccountTrafficStats ats " +
                        "where ats.accountId in ?1 and ats.periodStart <= ?2 and ats.periodEnd > ?2 " +
                        "group by ats.accountId",
                Object[].class)
            .setParameter(1, accountIds)
            .setParameter(2, currentTime)
            .getResultList();

        for (Object[] row : rows) {
            result.put((Long) row[0], row[1] == null ? 0L : ((Number) row[1]).longValue());
        }
        return result;
    }

    /**
     * 查找指定账户在指定时间范围内的流量统计记录（当前时间在时间范围内）
     */
    public List<AccountTrafficStats> findByAccountIdAndCurrentTime(Long accountId, LocalDateTime currentTime) {
        return find("accountId = ?1 and periodStart <= ?2 and periodEnd > ?2", accountId, currentTime).list();
    }

    /**
     * 批量查询多个账户当前周期的流量统计记录，结果按 accountId 去重取第一条
     */
    public Map<Long, AccountTrafficStats> findCurrentPeriodByAccountIds(List<Long> accountIds, LocalDateTime now) {
        if (accountIds == null || accountIds.isEmpty()) return Map.of();
        return find("accountId in ?1 and periodStart <= ?2 and periodEnd > ?2", accountIds, now).list()
                .stream()
                .collect(Collectors.toMap(AccountTrafficStats::getAccountId, s -> s, (a, b) -> a));
    }

    /**
     * 批量查询多个账户当前周期的上传/下载字节数，返回 accountId -> long[]{upload, download}
     */
    public Map<Long, long[]> sumUploadDownloadByAccountIds(List<Long> accountIds, LocalDateTime now) {
        if (accountIds == null || accountIds.isEmpty()) return Map.of();
        Map<Long, long[]> result = new HashMap<>();
        getEntityManager().createQuery(
                "select ats.accountId, sum(ats.uploadBytes), sum(ats.downloadBytes) " +
                "from AccountTrafficStats ats " +
                "where ats.accountId in ?1 and ats.periodStart <= ?2 and ats.periodEnd > ?2 " +
                "group by ats.accountId",
                Object[].class)
                .setParameter(1, accountIds)
                .setParameter(2, now)
                .getResultList()
                .forEach(row -> result.put(
                        (Long) row[0],
                        new long[]{
                                row[1] == null ? 0L : ((Number) row[1]).longValue(),
                                row[2] == null ? 0L : ((Number) row[2]).longValue()
                        }
                ));
        return result;
    }

    public long deleteByAccountId(Long accountId) {
        return delete("accountId", accountId);
    }

    @Transactional
    public int deleteExpiredStatsBatch(LocalDateTime cutoffTime, int batchSize) {
        return getEntityManager().createNativeQuery("""
                        DELETE FROM account_traffic_stats
                        WHERE id IN (
                            SELECT id FROM (
                                SELECT id
                                FROM account_traffic_stats
                                WHERE period_end < ?1
                                ORDER BY period_end ASC, id ASC
                                LIMIT ?2
                            ) delete_candidates
                        )
                        """)
                .setParameter(1, cutoffTime)
                .setParameter(2, Math.max(batchSize, 1))
                .executeUpdate();
    }
}
