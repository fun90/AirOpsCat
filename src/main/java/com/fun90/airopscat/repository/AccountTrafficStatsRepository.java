package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AccountTrafficStatsRepository implements PanacheRepository<AccountTrafficStats> {

    public Long sumUploadBytesByAccountId(Long accountId) {
        return find("select sum(uploadBytes) from AccountTrafficStats where accountId = ?1 and ?2 between periodStart and periodEnd", accountId, LocalDateTime.now())
                .project(Long.class)
                .firstResult();
    }

    public Long sumDownloadBytesByAccountId(Long accountId) {
        return find("select sum(downloadBytes) from AccountTrafficStats where accountId = ?1 and ?2 between periodStart and periodEnd", accountId, LocalDateTime.now())
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
                        "where ats.accountId in ?1 and ?2 between ats.periodStart and ats.periodEnd " +
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
     * @param accountId 账户ID
     * @param currentTime 当前时间
     * @return 匹配的流量统计记录列表
     */
    public List<AccountTrafficStats> findByAccountIdAndCurrentTime(Long accountId, LocalDateTime currentTime) {
        return find("accountId = ?1 and ?2 between periodStart and periodEnd", accountId, currentTime).list();
    }
}
