package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Account;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AccountRepository implements PanacheRepository<Account> {

    public Optional<Account> findByUuid(String uuid) {
        return find("uuid", uuid).firstResultOptional();
    }

    public Optional<Account> findByAccountNo(String accountNo) {
        return find("accountNo", accountNo).firstResultOptional();
    }
    
    public Optional<Account> findByAuthCode(String authCode) {
        return find("authCode", authCode).firstResultOptional();
    }
    
    public List<Account> findByUserId(Long userId) {
        return find("userId", userId).list();
    }
    
    public List<Account> findByDisabled(Integer disabled) {
        return find("disabled", disabled).list();
    }

    public List<Account> findExpiringAccounts(LocalDateTime date) {
        return find("toDate is not null and toDate <= ?1", date).list();
    }
    
    public List<Account> findAccountsExpiringBetween(LocalDateTime startDate, LocalDateTime endDate) {
        return find("toDate is not null and toDate between ?1 and ?2", startDate, endDate).list();
    }
    
    public long countActiveAccounts(LocalDateTime now) {
        return count("disabled = 0 and (toDate is null or toDate > ?1)", now);
    }
    
    public long countExpiredAccounts(LocalDateTime now) {
        return count("toDate is not null and toDate < ?1", now);
    }
    
    public long countDisabledAccounts() {
        return count("disabled = 1");
    }
    
    public long countExpiringInOneWeek(LocalDateTime now, LocalDateTime inOneWeek) {
        return count("toDate is not null and toDate between ?1 and ?2", now, inOneWeek);
    }
    
    public long countByUserId(Long userId) {
        return count("userId", userId);
    }
    
    public List<Account> findExpiredButNotDisabledAccounts(LocalDateTime now) {
        return find("disabled = 0 and toDate is not null and toDate < ?1", now).list();
    }
    
    @Transactional
    public int disableExpiredAccounts(List<Long> accountIds, LocalDateTime updateTime) {
        return update("disabled = 1, updateTime = ?1 where id in ?2", updateTime, accountIds);
    }

    public List<Account> findByAccountNoIn(List<String> accountNos) {
        return find("accountNo in ?1", accountNos).list();
    }

    public List<Account> findActiveAccountsByUserId(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return find("userId = ?1 and disabled = 0 and (toDate is null or toDate > ?2)", userId, now).list();
    }

    public List<Account> findAccountsByLevelAndActive(Integer level, boolean active) {
        if (active) {
            LocalDateTime now = LocalDateTime.now();
            return find("level = ?1 and disabled = 0 and (toDate is null or toDate > ?2)", level, now).list();
        } else {
            return find("level = ?1 and (disabled = 1 or (toDate is not null and toDate <= ?2))", 
                       level, LocalDateTime.now()).list();
        }
    }
}