package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountTrafficStatsDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.util.TrafficPeriodUtils;
import com.fun90.airopscat.repository.UserRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class AccountTrafficStatsService {

    private static final int DEFAULT_RETENTION_DAYS = 180;
    private static final int DEFAULT_CLEANUP_BATCH_SIZE = 1000;

    private final AccountTrafficStatsRepository accountTrafficStatsRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final SystemConfigService systemConfigService;

    @Inject
    public AccountTrafficStatsService(AccountTrafficStatsRepository accountTrafficStatsRepository,
                                      AccountRepository accountRepository,
                                      UserRepository userRepository,
                                      EntityManager entityManager,
                                      SystemConfigService systemConfigService) {
        this.accountTrafficStatsRepository = accountTrafficStatsRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.systemConfigService = systemConfigService;
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<AccountTrafficStats> getStatsPage(Long userId,
                                                                                           Long accountId,
                                                                                           LocalDateTime startDate,
                                                                                           LocalDateTime endDate) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (userId != null) {
            query.append(" and userId = :userId");
            params.put("userId", userId);
        }

        if (accountId != null) {
            query.append(" and accountId = :accountId");
            params.put("accountId", accountId);
        }

        if (startDate != null) {
            query.append(" and periodStart >= :startDate");
            params.put("startDate", startDate);
        }

        if (endDate != null) {
            query.append(" and periodEnd <= :endDate");
            params.put("endDate", endDate);
        }

        return accountTrafficStatsRepository.find(query.toString(), Sort.by("periodEnd").descending(), params);
    }

    public Map<String, Object> getStatsListPage(String search, LocalDateTime startDate, LocalDateTime endDate,
                                                int page, int size, String sortBy, String sortDirection) {
        TrafficStatsQueryContext queryContext = buildQueryContext(search, startDate, endDate);
        String totalBytesExpr = "(coalesce(ats.uploadBytes, 0) + coalesce(ats.downloadBytes, 0))";
        String orderClause = buildOrderClause(sortBy, sortDirection, totalBytesExpr);

        String selectQuery = "select new com.fun90.airopscat.model.dto.AccountTrafficStatsDto(" +
                "ats.id, ats.userId, a.remark, ats.accountId, ats.periodStart, ats.periodEnd, " +
                "coalesce(ats.uploadBytes, 0), coalesce(ats.downloadBytes, 0), " +
                totalBytesExpr + ", ats.bandwidthQuota) " +
                queryContext.fromClause() +
                queryContext.whereClause() + orderClause;

        TypedQuery<AccountTrafficStatsDto> listQuery = entityManager.createQuery(selectQuery, AccountTrafficStatsDto.class);
        applyParams(listQuery, queryContext.params());
        listQuery.setFirstResult(Math.max(page - 1, 0) * size);
        listQuery.setMaxResults(size);
        List<AccountTrafficStatsDto> records = listQuery.getResultList();

        String countQueryString = "select count(ats.id) " + queryContext.fromClause() + queryContext.whereClause();
        TypedQuery<Long> countQuery = entityManager.createQuery(countQueryString, Long.class);
        applyParams(countQuery, queryContext.params());
        long total = countQuery.getSingleResult();

        Map<String, Object> response = new HashMap<>();
        response.put("records", records);
        response.put("total", total);
        response.put("pages", total == 0 ? 0 : (int) Math.ceil((double) total / size));
        response.put("current", page);
        response.put("size", size);
        return response;
    }

    public Map<String, Object> getStatsSummary(String search, LocalDateTime startDate, LocalDateTime endDate) {
        TrafficStatsQueryContext queryContext = buildQueryContext(search, startDate, endDate);
        String summaryQueryString = "select coalesce(sum(ats.uploadBytes), 0), coalesce(sum(ats.downloadBytes), 0) "
                + queryContext.fromClause() + queryContext.whereClause();
        TypedQuery<Object[]> summaryQuery = entityManager.createQuery(summaryQueryString, Object[].class);
        applyParams(summaryQuery, queryContext.params());
        Object[] summary = summaryQuery.getSingleResult();
        long totalUpload = summary[0] == null ? 0L : ((Number) summary[0]).longValue();
        long totalDownload = summary[1] == null ? 0L : ((Number) summary[1]).longValue();

        Map<String, Object> response = new HashMap<>();
        response.put("totalUpload", totalUpload);
        response.put("totalDownload", totalDownload);
        response.put("totalUploadFormatted", formatBytes(totalUpload));
        response.put("totalDownloadFormatted", formatBytes(totalDownload));
        return response;
    }

    private String buildOrderClause(String sortBy, String sortDirection, String totalBytesExpr) {
        String direction = "asc".equalsIgnoreCase(sortDirection) ? "asc" : "desc";
        if ("totalBytes".equalsIgnoreCase(sortBy)) {
            return " order by " + totalBytesExpr + " " + direction + ", ats.id desc";
        }
        return " order by ats.id desc";
    }

    private void applyParams(Query query, Map<String, Object> params) {
        params.forEach(query::setParameter);
    }

    private TrafficStatsQueryContext buildQueryContext(String search, LocalDateTime startDate, LocalDateTime endDate) {
        StringBuilder whereClause = new StringBuilder(" where 1=1");
        Map<String, Object> params = new HashMap<>();

        if (search != null && !search.isBlank()) {
            whereClause.append(" and lower(coalesce(a.remark, '')) like :search");
            params.put("search", "%" + search.trim().toLowerCase() + "%");
        }

        if (startDate != null) {
            whereClause.append(" and ats.periodEnd >= :startDate");
            params.put("startDate", startDate);
        }

        if (endDate != null) {
            whereClause.append(" and ats.periodStart <= :endDate");
            params.put("endDate", endDate);
        }

        return new TrafficStatsQueryContext(
                "from AccountTrafficStats ats left join Account a on a.id = ats.accountId",
                whereClause.toString(),
                params
        );
    }

    public LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.parse(value).toLocalDateTime();
        }
    }

    public AccountTrafficStats getStatsById(Long id) {
        return accountTrafficStatsRepository.findById(id);
    }

    public List<AccountTrafficStats> getStatsByAccountAndCurrentTime(Long accountId, LocalDateTime currentTime) {
        return accountTrafficStatsRepository.findByAccountIdAndCurrentTime(accountId, currentTime);
    }

    /**
     * 获取账户当前有效流量配额（GB）：当前周期配额优先，不存在则取账户基准值。
     * 返回 null 表示不限量。
     */
    public Long getEffectiveBandwidth(Long accountId) {
        List<AccountTrafficStats> currentStats = accountTrafficStatsRepository.findByAccountIdAndCurrentTime(accountId, LocalDateTime.now());
        if (!currentStats.isEmpty()) {
            Long quota = currentStats.getFirst().getBandwidthQuota();
            if (quota != null) {
                return quota;
            }
        }
        Account account = accountRepository.findById(accountId);
        return account != null && account.getBandwidth() != null ? account.getBandwidth().longValue() : null;
    }

    @Transactional
    public AccountTrafficStats saveStats(AccountTrafficStats stats) {
        if (stats.getUserId() != null && userRepository.findById(stats.getUserId()) == null) {
            throw new EntityNotFoundException("User with ID " + stats.getUserId() + " not found");
        }

        accountTrafficStatsRepository.persist(stats);
        return stats;
    }

    @Transactional
    public AccountTrafficStats updateStats(AccountTrafficStats stats) {
        AccountTrafficStats existingStats = accountTrafficStatsRepository.findById(stats.getId());
        if (existingStats == null) {
            throw new EntityNotFoundException("Traffic stats not found");
        }

        copyNonNullProperties(stats, existingStats);
        return existingStats;
    }

    private void copyNonNullProperties(AccountTrafficStats src, AccountTrafficStats target) {
        if (src.getUserId() != null) {
            target.setUserId(src.getUserId());
        }
        if (src.getAccountId() != null) {
            target.setAccountId(src.getAccountId());
        }
        if (src.getPeriodStart() != null) {
            target.setPeriodStart(src.getPeriodStart());
        }
        if (src.getPeriodEnd() != null) {
            target.setPeriodEnd(src.getPeriodEnd());
        }
        if (src.getBandwidthQuota() != null) {
            target.setBandwidthQuota(src.getBandwidthQuota());
        }
        if (src.getUploadBytes() != null) {
            target.setUploadBytes(src.getUploadBytes());
        }
        if (src.getDownloadBytes() != null) {
            target.setDownloadBytes(src.getDownloadBytes());
        }
    }

    @Transactional
    public void updateBandwidthQuota(Long id, Long bandwidthQuota) {
        AccountTrafficStats stats = accountTrafficStatsRepository.findById(id);
        if (stats == null) {
            throw new jakarta.persistence.EntityNotFoundException("Traffic stats not found");
        }
        stats.setBandwidthQuota(bandwidthQuota);
    }

    @Transactional
    public void deleteStats(Long id) {
        accountTrafficStatsRepository.deleteById(id);
    }

    @Transactional
    public AccountTrafficStats saveOrUpdateTrafficStats(Long accountId, Long userId, String periodType, LocalDateTime toDate,
                                                        long uploadBytes, long downloadBytes) {
        LocalDateTime currentTime = LocalDateTime.now();
        List<AccountTrafficStats> existingStats = getStatsByAccountAndCurrentTime(accountId, currentTime);

        if (!existingStats.isEmpty()) {
            AccountTrafficStats stats = existingStats.getFirst();
            stats.setUploadBytes(stats.getUploadBytes() + uploadBytes);
            stats.setDownloadBytes(stats.getDownloadBytes() + downloadBytes);
            return stats;
        }

        AccountTrafficStats newStats = new AccountTrafficStats();
        newStats.setUserId(userId);
        newStats.setAccountId(accountId);
        LocalDateTime periodStart = TrafficPeriodUtils.resolveAccountPeriodStart(currentTime, toDate, periodType);
        newStats.setPeriodStart(periodStart);
        newStats.setPeriodEnd(TrafficPeriodUtils.resolveAccountPeriodEnd(currentTime, toDate, periodType));
        newStats.setUploadBytes(uploadBytes);
        newStats.setDownloadBytes(downloadBytes);
        Account account = accountRepository.findById(accountId);
        if (account != null) {
            newStats.setBandwidthQuota(account.getBandwidth() != null ? account.getBandwidth().longValue() : null);
        }
        accountTrafficStatsRepository.persist(newStats);
        return newStats;
    }

    @Transactional
    public long cleanupExpiredStats() {
        int retentionDays = getRetentionDays();
        int batchSize = getCleanupBatchSize();
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        long totalDeleted = 0L;
        int rounds = 0;

        while (true) {
            int deleted = deleteExpiredStatsBatch(cutoffTime, batchSize);
            if (deleted <= 0) {
                break;
            }
            totalDeleted += deleted;
            rounds++;
            if (deleted < batchSize) {
                break;
            }
        }

        logCleanupSummary(retentionDays, batchSize, cutoffTime, rounds, totalDeleted);
        return totalDeleted;
    }

    int getRetentionDays() {
        return Math.max(systemConfigService.getIntValue("airopscat.account.traffic.retention-days", DEFAULT_RETENTION_DAYS), 1);
    }

    int getCleanupBatchSize() {
        return Math.max(systemConfigService.getIntValue("airopscat.account.traffic.cleanup.batch-size",
                DEFAULT_CLEANUP_BATCH_SIZE), 1);
    }

    int deleteExpiredStatsBatch(LocalDateTime cutoffTime, int batchSize) {
        return accountTrafficStatsRepository.deleteExpiredStatsBatch(cutoffTime, batchSize);
    }

    void logCleanupSummary(int retentionDays, int batchSize, LocalDateTime cutoffTime, int rounds, long totalDeleted) {
        log.info("账户流量统计清理完成，保留天数: {}, 截止时间: {}, 批大小: {}, 批次数: {}, 删除总数: {}",
                retentionDays, cutoffTime, batchSize, rounds, totalDeleted);
    }

    public String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    private record TrafficStatsQueryContext(String fromClause, String whereClause, Map<String, Object> params) {
    }
}
