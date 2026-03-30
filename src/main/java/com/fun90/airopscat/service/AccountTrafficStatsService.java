package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountTrafficStatsDto;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AccountTrafficStatsService {

    private final AccountTrafficStatsRepository accountTrafficStatsRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    @Inject
    public AccountTrafficStatsService(AccountTrafficStatsRepository accountTrafficStatsRepository,
                                      UserRepository userRepository,
                                      EntityManager entityManager) {
        this.accountTrafficStatsRepository = accountTrafficStatsRepository;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
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

        String totalBytesExpr = "(coalesce(ats.uploadBytes, 0) + coalesce(ats.downloadBytes, 0))";
        String orderClause = buildOrderClause(sortBy, sortDirection, totalBytesExpr);

        String selectQuery = "select new com.fun90.airopscat.model.dto.AccountTrafficStatsDto(" +
                "ats.id, ats.userId, a.remark, ats.accountId, ats.periodStart, ats.periodEnd, " +
                "coalesce(ats.uploadBytes, 0), coalesce(ats.downloadBytes, 0), " +
                totalBytesExpr + ") " +
                "from AccountTrafficStats ats left join Account a on a.id = ats.accountId" +
                whereClause + orderClause;

        TypedQuery<AccountTrafficStatsDto> listQuery = entityManager.createQuery(selectQuery, AccountTrafficStatsDto.class);
        applyParams(listQuery, params);
        listQuery.setFirstResult(Math.max(page - 1, 0) * size);
        listQuery.setMaxResults(size);
        List<AccountTrafficStatsDto> records = listQuery.getResultList();

        String countQueryString = "select count(ats.id) from AccountTrafficStats ats left join Account a on a.id = ats.accountId"
                + whereClause;
        TypedQuery<Long> countQuery = entityManager.createQuery(countQueryString, Long.class);
        applyParams(countQuery, params);
        long total = countQuery.getSingleResult();

        String summaryQueryString = "select coalesce(sum(ats.uploadBytes), 0), coalesce(sum(ats.downloadBytes), 0) " +
                "from AccountTrafficStats ats left join Account a on a.id = ats.accountId" + whereClause;
        TypedQuery<Object[]> summaryQuery = entityManager.createQuery(summaryQueryString, Object[].class);
        applyParams(summaryQuery, params);
        Object[] summary = summaryQuery.getSingleResult();
        long totalUpload = summary[0] == null ? 0L : ((Number) summary[0]).longValue();
        long totalDownload = summary[1] == null ? 0L : ((Number) summary[1]).longValue();

        Map<String, Object> response = new HashMap<>();
        response.put("records", records);
        response.put("total", total);
        response.put("pages", total == 0 ? 0 : (int) Math.ceil((double) total / size));
        response.put("current", page);
        response.put("size", size);
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
        if (src.getUploadBytes() != null) {
            target.setUploadBytes(src.getUploadBytes());
        }
        if (src.getDownloadBytes() != null) {
            target.setDownloadBytes(src.getDownloadBytes());
        }
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
        accountTrafficStatsRepository.persist(newStats);
        return newStats;
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
}
