package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountOnlineIp;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 在线IP记录仓库
 */
@ApplicationScoped
public class AccountOnlineIpRepository implements PanacheRepository<AccountOnlineIp> {

    /**
     * 根据accountNo查找在指定时间之后的在线记录
     */
    public List<AccountOnlineIp> findByAccountNoAndLastOnlineTimeAfter(String accountNo, LocalDateTime afterTime) {
        return find("accountNo = ?1 and lastOnlineTime > ?2 order by lastOnlineTime desc", accountNo, afterTime).list();
    }
    
    /**
     * 查找在指定时间之后的所有在线记录
     */
    public List<AccountOnlineIp> findByLastOnlineTimeAfter(LocalDateTime afterTime) {
        return find("lastOnlineTime > ?1 order by lastOnlineTime desc", afterTime).list();
    }
    
    /**
     * 根据nodeIp查找在线记录
     */
    public List<AccountOnlineIp> findByNodeIp(String nodeIp) {
        return find("nodeIp = ?1 order by lastOnlineTime desc", nodeIp).list();
    }

    /**
     * 批量查询各accountNo的最近一次在线时间
     */
    @SuppressWarnings("unchecked")
    public Map<String, LocalDateTime> findMaxLastOnlineTimeMapByAccountNos(List<String> accountNos) {
        if (accountNos == null || accountNos.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = getEntityManager()
                .createQuery("SELECT a.accountNo, MAX(a.lastOnlineTime) FROM AccountOnlineIp a WHERE a.accountNo IN :accountNos GROUP BY a.accountNo")
                .setParameter("accountNos", accountNos)
                .getResultList();
        return rows.stream()
                .filter(row -> row[0] != null && row[1] != null)
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (LocalDateTime) row[1]
                ));
    }

    /**
     * 查找指定accountNo最近一次在线时间（不限时间范围）
     */
    public LocalDateTime findMaxLastOnlineTimeByAccountNo(String accountNo) {
        return find("accountNo = ?1 order by lastOnlineTime desc", accountNo)
                .firstResultOptional()
                .map(AccountOnlineIp::getLastOnlineTime)
                .orElse(null);
    }

    public long deleteByAccountNo(String accountNo) {
        return delete("accountNo", accountNo);
    }

    /**
     * 删除过期的在线记录
     */
    @Transactional
    public void deleteExpiredRecords(LocalDateTime expireTime) {
        delete("lastOnlineTime < ?1", expireTime);
    }
    
    /**
     * 使用 MySQL 原生 UPSERT 原子更新在线状态。
     * 如果距离上次续期超过离线阈值，则重新开始计算本次在线会话时间。
     */
    @Transactional
    public void upsertOnlineStatus(String accountNo,
                                   String clientIp,
                                   String nodeIp,
                                   LocalDateTime lastOnlineTime,
                                   LocalDateTime sessionStartTime,
                                   LocalDateTime createTime,
                                   LocalDateTime updateTime,
                                   LocalDateTime offlineThresholdTime) {
        getEntityManager().createNativeQuery(
            "INSERT INTO account_online_ip (account_no, client_ip, node_ip, last_online_time, session_start_time, create_time, update_time) " +
            "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) " +
            "ON DUPLICATE KEY UPDATE " +
            "session_start_time = CASE " +
            "WHEN account_online_ip.last_online_time IS NULL OR account_online_ip.last_online_time <= ?8 THEN VALUES(session_start_time) " +
            "WHEN account_online_ip.session_start_time IS NULL THEN COALESCE(account_online_ip.create_time, VALUES(session_start_time)) " +
            "ELSE account_online_ip.session_start_time END, " +
            "last_online_time = VALUES(last_online_time), " +
            "update_time = VALUES(update_time)")
            .setParameter(1, accountNo)
            .setParameter(2, clientIp)
            .setParameter(3, nodeIp)
            .setParameter(4, lastOnlineTime)
            .setParameter(5, sessionStartTime)
            .setParameter(6, createTime)
            .setParameter(7, updateTime)
            .setParameter(8, offlineThresholdTime)
            .executeUpdate();
    }
}
