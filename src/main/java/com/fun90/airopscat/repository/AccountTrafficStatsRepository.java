package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class AccountTrafficStatsRepository implements PanacheRepository<AccountTrafficStats> {

    public List<AccountTrafficStats> findByUserId(Long userId) {
        return find("userId", userId).list();
    }
    
    public List<AccountTrafficStats> findByAccountId(Long accountId) {
        return find("accountId", accountId).list();
    }
    
    public List<AccountTrafficStats> findByUserId(Long userId, Page page) {
        return find("userId", userId).page(page).list();
    }
    
    public List<AccountTrafficStats> findByUserIdAndPeriod(Long userId, LocalDateTime startDate, LocalDateTime endDate) {
        return find("userId = ?1 and periodStart >= ?2 and periodEnd <= ?3", userId, startDate, endDate).list();
    }
    
    public List<AccountTrafficStats> findByAccountIdAndPeriod(Long accountId, LocalDateTime startDate, LocalDateTime endDate) {
        return find("accountId = ?1 and periodStart >= ?2 and periodEnd <= ?3", accountId, startDate, endDate).list();
    }
    
    public Long sumUploadBytesByUserId(Long userId) {
        return find("select sum(uploadBytes) from AccountTrafficStats where userId = ?1", userId)
                .project(Long.class)
                .firstResult();
    }
    
    public Long sumDownloadBytesByUserId(Long userId) {
        return find("select sum(downloadBytes) from AccountTrafficStats where userId = ?1", userId)
                .project(Long.class)
                .firstResult();
    }
    
    public Long sumUploadBytesByAccountId(Long accountId) {
        return find("select sum(uploadBytes) from AccountTrafficStats where accountId = ?1", accountId)
                .project(Long.class)
                .firstResult();
    }
    
    public Long sumDownloadBytesByAccountId(Long accountId) {
        return find("select sum(downloadBytes) from AccountTrafficStats where accountId = ?1", accountId)
                .project(Long.class)
                .firstResult();
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