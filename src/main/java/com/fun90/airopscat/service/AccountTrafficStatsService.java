package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.UserRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AccountTrafficStatsService {

    private final AccountTrafficStatsRepository accountTrafficStatsRepository;
    private final UserRepository userRepository;

    @Inject
    public AccountTrafficStatsService(AccountTrafficStatsRepository accountTrafficStatsRepository, 
                                     UserRepository userRepository) {
        this.accountTrafficStatsRepository = accountTrafficStatsRepository;
        this.userRepository = userRepository;
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<AccountTrafficStats> getStatsPage(String search, Long userId, Long accountId,
                                                LocalDateTime startDate, LocalDateTime endDate) {
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

    public AccountTrafficStats getStatsById(Long id) {
        return accountTrafficStatsRepository.findById(id);
    }

    public List<AccountTrafficStats> getStatsByAccountAndCurrentTime(Long accountId, LocalDateTime currentTime) {
        return accountTrafficStatsRepository.findByAccountIdAndCurrentTime(accountId, currentTime);
    }

    public Long getTotalUploadByUser(Long userId) {
        Long sum = accountTrafficStatsRepository.sumUploadBytesByUserId(userId);
        return sum != null ? sum : 0L;
    }

    public Long getTotalDownloadByUser(Long userId) {
        Long sum = accountTrafficStatsRepository.sumDownloadBytesByUserId(userId);
        return sum != null ? sum : 0L;
    }

    public Long getTotalUploadByAccount(Long accountId) {
        Long sum = accountTrafficStatsRepository.sumUploadBytesByAccountId(accountId);
        return sum != null ? sum : 0L;
    }

    public Long getTotalDownloadByAccount(Long accountId) {
        Long sum = accountTrafficStatsRepository.sumDownloadBytesByAccountId(accountId);
        return sum != null ? sum : 0L;
    }

    @Transactional
    public AccountTrafficStats saveStats(AccountTrafficStats stats) {
        // Ensure user exists
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

        // Copy non-null properties manually
        copyNonNullProperties(stats, existingStats);

        // No need to call save/persist for updates in Panache
        return existingStats;
    }

    // Copy non-null properties manually
    private void copyNonNullProperties(AccountTrafficStats src, AccountTrafficStats target) {
        if (src.getUserId() != null) target.setUserId(src.getUserId());
        if (src.getAccountId() != null) target.setAccountId(src.getAccountId());
        if (src.getPeriodStart() != null) target.setPeriodStart(src.getPeriodStart());
        if (src.getPeriodEnd() != null) target.setPeriodEnd(src.getPeriodEnd());
        if (src.getUploadBytes() != null) target.setUploadBytes(src.getUploadBytes());
        if (src.getDownloadBytes() != null) target.setDownloadBytes(src.getDownloadBytes());
    }

    @Transactional
    public void deleteStats(Long id) {
        accountTrafficStatsRepository.deleteById(id);
    }
    
    /**
     * 智能保存或更新流量统计
     * 如果在当前时间段内已有记录，则累加流量；否则创建新记录
     */
    @Transactional
    public AccountTrafficStats saveOrUpdateTrafficStats(Long accountId, Long userId, String periodType, 
                                                       long uploadBytes, long downloadBytes) {
        LocalDateTime currentTime = LocalDateTime.now();
        
        // 查找当前时间段内的流量统计记录
        List<AccountTrafficStats> existingStats = getStatsByAccountAndCurrentTime(accountId, currentTime);
        
        if (!existingStats.isEmpty()) {
            // 如果找到记录，累加流量数据
            AccountTrafficStats stats = existingStats.getFirst();
            stats.setUploadBytes(stats.getUploadBytes() + uploadBytes);
            stats.setDownloadBytes(stats.getDownloadBytes() + downloadBytes);
            // No need to call save/persist for updates in Panache
            return stats;
        } else {
            // 如果没有找到记录，创建新记录
            AccountTrafficStats newStats = new AccountTrafficStats();
            newStats.setUserId(userId);
            newStats.setAccountId(accountId);
            newStats.setPeriodStart(currentTime);
            newStats.setPeriodEnd(calculatePeriodEnd(currentTime, periodType));
            newStats.setUploadBytes(uploadBytes);
            newStats.setDownloadBytes(downloadBytes);
            accountTrafficStatsRepository.persist(newStats);
            return newStats;
        }
    }
    
    /**
     * 根据统计周期类型计算周期结束时间
     */
    private LocalDateTime calculatePeriodEnd(LocalDateTime periodStart, String periodType) {
        return switch (periodType.toUpperCase()) {
            case "MONTHLY" ->
                // 月周期：从当前时间开始，1个月后
                    periodStart.plusMonths(1).minusNanos(1);
            case "YEARLY" ->
                // 年周期：从当前时间开始，1年后
                    periodStart.plusYears(1).minusNanos(1);
            default ->
                // 默认使用月周期
                    periodStart.plusMonths(1).minusNanos(1);
        };
    }

    // 格式化流量大小 (B, KB, MB, GB)
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