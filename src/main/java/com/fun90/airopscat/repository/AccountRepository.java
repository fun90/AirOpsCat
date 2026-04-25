package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Account;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class AccountRepository implements PanacheRepository<Account> {

    public List<Account> findByAccountNos(Set<String> accountNos) {
        return find("accountNo in ?1", accountNos).list();
    }

    public List<String> findExistingAccountNos(List<String> accountNos) {
        if (accountNos == null || accountNos.isEmpty()) {
            return List.of();
        }
        return find("accountNo in ?1", accountNos)
                .<Account>list()
                .stream()
                .map(Account::getAccountNo)
                .collect(java.util.stream.Collectors.toList());
    }

    public List<Account> findActiveRateLimitedAccounts(LocalDateTime now) {
        return find("disabled = 0 and speed is not null and speed > 0 and (toDate is null or toDate > ?1)", now).list();
    }

    public List<Account> findActiveConnectionLimitedAccounts(LocalDateTime now) {
        return find("disabled = 0 and maxConnections is not null and maxConnections > 0 and (toDate is null or toDate > ?1)", now).list();
    }
    
    public Optional<Account> findByAuthCode(String authCode) {
        return find("authCode", authCode).firstResultOptional();
    }

    public long countByUserId(Long userId) {
        return count("userId", userId);
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
    
    
    public List<Account> findExpiredButNotDisabledAccounts(LocalDateTime now) {
        return find("disabled = 0 and toDate is not null and toDate < ?1", now).list();
    }

    public List<Account> findExpiringOnDate(LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return find("disabled = 0 and toDate is not null and toDate between ?1 and ?2", startOfDay, endOfDay).list();
    }
    
    @Transactional
    public int disableExpiredAccounts(List<Long> accountIds, LocalDateTime updateTime) {
        return update("disabled = 1, updateTime = ?1 where id in ?2", updateTime, accountIds);
    }

}
