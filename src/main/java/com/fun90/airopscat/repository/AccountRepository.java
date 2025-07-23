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

    public Optional<Account> findByAccountNo(String accountNo) {
        return find("accountNo", accountNo).firstResultOptional();
    }
    
    public Optional<Account> findByAuthCode(String authCode) {
        return find("authCode", authCode).firstResultOptional();
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
    
    @Transactional
    public int disableExpiredAccounts(List<Long> accountIds, LocalDateTime updateTime) {
        return update("disabled = 1, updateTime = ?1 where id in ?2", updateTime, accountIds);
    }

}