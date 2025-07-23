package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Transaction;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class TransactionRepository implements PanacheRepository<Transaction> {

    public List<Transaction> findByBusinessTableAndBusinessId(String businessTable, Long businessId) {
        return find("businessTable = ?1 and businessId = ?2", businessTable, businessId).list();
    }
    
    
    public BigDecimal getTotalIncome() {
        return find("select sum(amount) from Transaction where type = 0")
                .project(BigDecimal.class)
                .firstResult();
    }
    
    public BigDecimal getTotalExpense() {
        return find("select sum(amount) from Transaction where type = 1")
                .project(BigDecimal.class)
                .firstResult();
    }
    
    public BigDecimal getNetBalance() {
        return find("select sum(case when type = 0 then amount else -amount end) from Transaction")
                .project(BigDecimal.class)
                .firstResult();
    }
    
    public BigDecimal getSumByTypeAndDateRange(Integer type, LocalDateTime startDate, LocalDateTime endDate) {
        return find("select sum(amount) from Transaction where type = ?1 and transactionDate between ?2 and ?3", 
                   type, startDate, endDate)
                .project(BigDecimal.class)
                .firstResult();
    }
}