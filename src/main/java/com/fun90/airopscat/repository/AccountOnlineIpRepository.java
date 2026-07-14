package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountOnlineIp;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
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

    public List<AccountOnlineIp> findByAccountNosAndLastOnlineTimeAfter(List<String> accountNos, LocalDateTime afterTime) {
        if (accountNos == null || accountNos.isEmpty()) {
            return List.of();
        }
        return find("accountNo in ?1 and lastOnlineTime > ?2 order by lastOnlineTime desc", accountNos, afterTime).list();
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

    public List<AccountOnlineIp> findByNodeIpAndLastOnlineTimeAfter(String nodeIp, LocalDateTime afterTime) {
        return find("nodeIp = ?1 and lastOnlineTime > ?2 order by lastOnlineTime desc", nodeIp, afterTime).list();
    }

    public List<AccountOnlineIp> findByNodeIdAndLastOnlineTimeAfter(Long nodeId, LocalDateTime afterTime) {
        return find("nodeId = ?1 and lastOnlineTime > ?2 order by lastOnlineTime desc", nodeId, afterTime).list();
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

    @SuppressWarnings("unchecked")
    public Map<String, Long> countByNodeIpsAndLastOnlineTimeAfter(List<String> nodeIps, LocalDateTime afterTime) {
        if (nodeIps == null || nodeIps.isEmpty()) {
            return Map.of();
        }

        List<Object[]> rows = getEntityManager()
                .createQuery("SELECT a.nodeIp, COUNT(a) FROM AccountOnlineIp a WHERE a.nodeIp IN :nodeIps AND a.lastOnlineTime > :afterTime GROUP BY a.nodeIp")
                .setParameter("nodeIps", nodeIps)
                .setParameter("afterTime", afterTime)
                .getResultList();

        Map<String, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                result.put((String) row[0], ((Number) row[1]).longValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<Long, Long> countByNodeIdsAndLastOnlineTimeAfter(List<Long> nodeIds, LocalDateTime afterTime) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Map.of();
        }

        List<Object[]> rows = getEntityManager()
                .createQuery("SELECT a.nodeId, COUNT(a) FROM AccountOnlineIp a WHERE a.nodeId IN :nodeIds AND a.lastOnlineTime > :afterTime GROUP BY a.nodeId")
                .setParameter("nodeIds", nodeIds)
                .setParameter("afterTime", afterTime)
                .getResultList();

        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                result.put((Long) row[0], ((Number) row[1]).longValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<Long, List<String>> findDistinctAccountNosByNodeAfter(LocalDateTime afterTime) {
        List<Object[]> rows = getEntityManager()
                .createQuery("SELECT a.nodeId, a.accountNo FROM AccountOnlineIp a " +
                        "WHERE a.nodeId IS NOT NULL AND a.accountNo IS NOT NULL AND a.lastOnlineTime > :afterTime " +
                        "GROUP BY a.nodeId, a.accountNo")
                .setParameter("afterTime", afterTime)
                .getResultList();

        Map<Long, List<String>> result = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null && row[1] != null) {
                result.computeIfAbsent((Long) row[0], ignored -> new java.util.ArrayList<>())
                        .add((String) row[1]);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> countByAccountNosAndLastOnlineTimeAfter(List<String> accountNos, LocalDateTime afterTime) {
        if (accountNos == null || accountNos.isEmpty()) {
            return Map.of();
        }

        List<Object[]> rows = getEntityManager()
                .createQuery("SELECT a.accountNo, COUNT(a) FROM AccountOnlineIp a WHERE a.accountNo IN :accountNos AND a.lastOnlineTime > :afterTime GROUP BY a.accountNo")
                .setParameter("accountNos", accountNos)
                .setParameter("afterTime", afterTime)
                .getResultList();

        Map<String, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                result.put((String) row[0], ((Number) row[1]).longValue());
            }
        }
        return result;
    }

    /**
     * 删除过期的在线记录
     */
    @Transactional
    public int deleteExpiredRecordsBatch(LocalDateTime expireTime, int batchSize) {
        return getEntityManager().createNativeQuery(
                        "DELETE FROM account_online_ip WHERE last_online_time < ?1 ORDER BY last_online_time LIMIT ?2")
                .setParameter(1, expireTime)
                .setParameter(2, Math.max(batchSize, 1))
                .executeUpdate();
    }
    
    /**
     * 使用 MySQL 原生 UPSERT 原子更新在线状态。
     * 如果距离上次续期超过离线阈值，则重新开始计算本次在线会话时间。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void upsertOnlineStatus(String accountNo,
                                   String clientIp,
                                   String connectionId,
                                   String nodeIp,
                                   Long nodeId,
                                   String nodeTag,
                                   LocalDateTime lastOnlineTime,
                                   LocalDateTime sessionStartTime,
                                   LocalDateTime createTime,
                                   LocalDateTime updateTime,
                                   LocalDateTime offlineThresholdTime) {
        getEntityManager().createNativeQuery(
            "INSERT INTO account_online_ip (account_no, client_ip, connection_id, node_ip, node_id, node_tag, last_online_time, session_start_time, create_time, update_time) " +
            "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10) " +
            "ON DUPLICATE KEY UPDATE " +
            "client_ip = VALUES(client_ip), " +
            "node_id = VALUES(node_id), " +
            "node_tag = VALUES(node_tag), " +
            "session_start_time = CASE " +
            "WHEN account_online_ip.last_online_time IS NULL OR account_online_ip.last_online_time <= ?11 THEN VALUES(session_start_time) " +
            "WHEN account_online_ip.session_start_time IS NULL THEN COALESCE(account_online_ip.create_time, VALUES(session_start_time)) " +
            "ELSE account_online_ip.session_start_time END, " +
            "last_online_time = VALUES(last_online_time), " +
            "update_time = VALUES(update_time)")
            .setParameter(1, accountNo)
            .setParameter(2, clientIp)
            .setParameter(3, connectionId)
            .setParameter(4, nodeIp)
            .setParameter(5, nodeId)
            .setParameter(6, nodeTag)
            .setParameter(7, lastOnlineTime)
            .setParameter(8, sessionStartTime)
            .setParameter(9, createTime)
            .setParameter(10, updateTime)
            .setParameter(11, offlineThresholdTime)
            .executeUpdate();
    }
}
