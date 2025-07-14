package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.AccountOnlineIp;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 在线IP记录仓库
 */
@ApplicationScoped
public class AccountOnlineIpRepository implements PanacheRepository<AccountOnlineIp> {

    /**
     * 根据accountNo、clientIp和nodeIp查找在线记录
     */
    public Optional<AccountOnlineIp> findByAccountNoAndClientIpAndNodeIp(String accountNo, String clientIp, String nodeIp) {
        return find("accountNo = ?1 and clientIp = ?2 and nodeIp = ?3", accountNo, clientIp, nodeIp).firstResultOptional();
    }
    
    /**
     * 根据accountNo查找在线记录
     */
    public List<AccountOnlineIp> findByAccountNo(String accountNo) {
        return find("accountNo", accountNo).list();
    }
    
    /**
     * 根据accountNo查找在指定时间之后的在线记录
     */
    public List<AccountOnlineIp> findByAccountNoAndLastOnlineTimeAfter(String accountNo, LocalDateTime afterTime) {
        return find("accountNo = ?1 and lastOnlineTime > ?2", accountNo, afterTime).list();
    }
    
    /**
     * 查找在指定时间之后的所有在线记录
     */
    public List<AccountOnlineIp> findByLastOnlineTimeAfter(LocalDateTime afterTime) {
        return find("lastOnlineTime > ?1", afterTime).list();
    }
    
    /**
     * 根据nodeIp查找在线记录
     */
    public List<AccountOnlineIp> findByNodeIp(String nodeIp) {
        return find("nodeIp", nodeIp).list();
    }
    
    /**
     * 删除指定accountNo的所有在线记录
     */
    @Transactional
    public void deleteByAccountNo(String accountNo) {
        delete("accountNo", accountNo);
    }
    
    /**
     * 删除过期的在线记录
     */
    @Transactional
    public void deleteExpiredRecords(LocalDateTime expireTime) {
        delete("lastOnlineTime < ?1", expireTime);
    }
    
    /**
     * 使用原生 SQL 的 INSERT ... ON CONFLICT 语句实现 upsert 操作
     * 如果记录存在则仅更新 last_online_time 和 update_time，否则插入新记录
     */
    @Transactional
    public void upsertOnlineStatus(String accountNo, String clientIp, String nodeIp, LocalDateTime lastOnlineTime, LocalDateTime createTime, LocalDateTime updateTime) {
        getEntityManager().createNativeQuery(
            "INSERT INTO account_online_ip (account_no, client_ip, node_ip, last_online_time, create_time, update_time) VALUES (?1, ?2, ?3, ?4, ?5, ?6) " +
            "ON CONFLICT(account_no, client_ip, node_ip) DO UPDATE SET " +
            "last_online_time = ?4, update_time = ?6")
            .setParameter(1, accountNo)
            .setParameter(2, clientIp)
            .setParameter(3, nodeIp)
            .setParameter(4, lastOnlineTime)
            .setParameter(5, createTime)
            .setParameter(6, updateTime)
            .executeUpdate();
    }
}