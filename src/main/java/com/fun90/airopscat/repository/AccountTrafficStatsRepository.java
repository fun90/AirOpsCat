package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class AccountTrafficStatsRepository implements PanacheRepository<AccountTrafficStats> {


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