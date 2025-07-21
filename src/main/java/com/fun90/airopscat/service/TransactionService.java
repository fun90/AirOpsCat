package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.TransactionDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.Transaction;
import com.fun90.airopscat.model.enums.TransactionType;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.DomainRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TransactionRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@ApplicationScoped
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final DomainRepository domainRepository;
    private final ServerRepository serverRepository;

    @Inject
    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            DomainRepository domainRepository,
            ServerRepository serverRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.domainRepository = domainRepository;
        this.serverRepository = serverRepository;
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Transaction> getTransactionPage(String search, Integer type, 
                                         String businessTable, Long businessId,
                                         LocalDateTime startDate, LocalDateTime endDate) {
        // Build query string
        StringBuilder queryBuilder = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        
        List<String> conditions = new ArrayList<>();
        
        // Search condition
        if (search != null && !search.trim().isEmpty()) {
            conditions.add("(lower(description) like :search or lower(remark) like :search or lower(paymentMethod) like :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
        
        // Type filter
        if (type != null) {
            conditions.add("type = :type");
            params.put("type", type);
        }
        
        // Business table filter
        if (businessTable != null && !businessTable.trim().isEmpty()) {
            conditions.add("businessTable = :businessTable");
            params.put("businessTable", businessTable);
        }
        
        // Business ID filter
        if (businessId != null) {
            conditions.add("businessId = :businessId");
            params.put("businessId", businessId);
        }
        
        // Date range filters
        if (startDate != null) {
            conditions.add("transactionDate >= :startDate");
            params.put("startDate", startDate);
        }
        
        if (endDate != null) {
            conditions.add("transactionDate <= :endDate");
            params.put("endDate", endDate);
        }
        
        String query = conditions.isEmpty() ? "" : String.join(" and ", conditions);
        Sort sort = Sort.by("transactionDate").descending();
        
        if (query.isEmpty()) {
            return transactionRepository.findAll(sort);
        } else {
            return transactionRepository.find(query, sort, params);
        }
    }

    public Transaction getTransactionById(Long id) {
        return transactionRepository.findById(id);
    }

    public List<Transaction> getByBusinessTableAndId(String businessTable, Long businessId) {
        return transactionRepository.findByBusinessTableAndBusinessId(businessTable, businessId);
    }

    public Map<String, Object> getTransactionStats() {
        Map<String, Object> stats = new HashMap<>();
        
        BigDecimal totalIncome = transactionRepository.getTotalIncome();
        BigDecimal totalExpense = transactionRepository.getTotalExpense();
        BigDecimal netBalance = transactionRepository.getNetBalance();
        
        stats.put("totalIncome", totalIncome != null ? totalIncome : BigDecimal.ZERO);
        stats.put("totalExpense", totalExpense != null ? totalExpense : BigDecimal.ZERO);
        stats.put("netBalance", netBalance != null ? netBalance : BigDecimal.ZERO);
        
        // Current month stats
        LocalDateTime startOfMonth = YearMonth.now().atDay(1).atStartOfDay();
        LocalDateTime endOfMonth = YearMonth.now().atEndOfMonth().atTime(23, 59, 59);
        
        BigDecimal currentMonthIncome = transactionRepository.getSumByTypeAndDateRange(0, startOfMonth, endOfMonth);
        BigDecimal currentMonthExpense = transactionRepository.getSumByTypeAndDateRange(1, startOfMonth, endOfMonth);
        
        stats.put("currentMonthIncome", currentMonthIncome != null ? currentMonthIncome : BigDecimal.ZERO);
        stats.put("currentMonthExpense", currentMonthExpense != null ? currentMonthExpense : BigDecimal.ZERO);
        
        return stats;
    }

    public TransactionDto convertToDto(Transaction transaction) {
        TransactionDto dto = new TransactionDto();
        // Manual property copying
        dto.setId(transaction.getId());
        dto.setType(transaction.getType());
        dto.setAmount(transaction.getAmount());
        dto.setDescription(transaction.getDescription());
        dto.setRemark(transaction.getRemark());
        dto.setPaymentMethod(transaction.getPaymentMethod());
        dto.setBusinessTable(transaction.getBusinessTable());
        dto.setBusinessId(transaction.getBusinessId());
        dto.setTransactionDate(transaction.getTransactionDate());
        
        // Set transaction type description
        TransactionType transactionType = TransactionType.fromValue(transaction.getType());
        if (transactionType != null) {
            dto.setTypeDescription(transactionType.getDescription());
        }
        
        // Get business name based on table and ID
        if (transaction.getBusinessTable() != null && transaction.getBusinessId() != null) {
            dto.setBusinessName(getBusinessName(transaction.getBusinessTable(), transaction.getBusinessId()));
        }
        
        return dto;
    }
    
    private String getBusinessName(String businessTable, Long businessId) {
        switch (businessTable.toLowerCase()) {
            case "account":
                Account account = accountRepository.findById(businessId);
                return account != null ? account.getRemark() : "未知账户";
            case "domain":
                Domain domain = domainRepository.findById(businessId);
                return domain != null ? domain.getDomain() : "未知域名";
            case "server":
                Server server = serverRepository.findById(businessId);
                return server != null ? server.getIp() + (server.getName() != null ? " (" + server.getName() + ")" : "") : "未知服务器";
            default:
                return "未知关联业务";
        }
    }

    @Transactional
    public Transaction saveTransaction(Transaction transaction) {
        // Set default values if not provided
        if (transaction.getTransactionDate() == null) {
            transaction.setTransactionDate(LocalDateTime.now());
        }
        
        transactionRepository.persist(transaction);
        return transaction;
    }

    @Transactional
    public Transaction updateTransaction(Transaction transaction) {
        Transaction existingTransaction = transactionRepository.findById(transaction.getId());
        if (existingTransaction == null) {
            throw new EntityNotFoundException("Transaction not found");
        }

        // 使用工具方法复制非null属性
        copyNonNullProperties(transaction, existingTransaction);

        // No need to call save/persist for updates in Panache
        return existingTransaction;
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(Transaction source, Transaction target) {
        if (source.getType() != null) target.setType(source.getType());
        if (source.getAmount() != null) target.setAmount(source.getAmount());
        if (source.getDescription() != null) target.setDescription(source.getDescription());
        if (source.getRemark() != null) target.setRemark(source.getRemark());
        if (source.getPaymentMethod() != null) target.setPaymentMethod(source.getPaymentMethod());
        if (source.getBusinessTable() != null) target.setBusinessTable(source.getBusinessTable());
        if (source.getBusinessId() != null) target.setBusinessId(source.getBusinessId());
        if (source.getTransactionDate() != null) target.setTransactionDate(source.getTransactionDate());
    }

    @Transactional
    public void deleteTransaction(Long id) {
        transactionRepository.deleteById(id);
    }
    
    /**
     * 获取最近几个月的收支统计
     * @param months 月数
     * @return 按月份统计的收支数据
     */
    public List<Map<String, Object>> getMonthlyStats(int months) {
        List<Map<String, Object>> monthlyStats = new ArrayList<>();
        
        LocalDateTime now = LocalDateTime.now();
        
        for (int i = 0; i < months; i++) {
            YearMonth yearMonth = YearMonth.now().minusMonths(i);
            LocalDateTime startOfMonth = yearMonth.atDay(1).atStartOfDay();
            LocalDateTime endOfMonth = yearMonth.atEndOfMonth().atTime(23, 59, 59);
            
            BigDecimal monthlyIncome = transactionRepository.getSumByTypeAndDateRange(0, startOfMonth, endOfMonth);
            BigDecimal monthlyExpense = transactionRepository.getSumByTypeAndDateRange(1, startOfMonth, endOfMonth);
            
            Map<String, Object> monthData = new HashMap<>();
            monthData.put("month", yearMonth.getMonth().toString() + " " + yearMonth.getYear());
            monthData.put("income", monthlyIncome != null ? monthlyIncome : BigDecimal.ZERO);
            monthData.put("expense", monthlyExpense != null ? monthlyExpense : BigDecimal.ZERO);
            monthData.put("balance", (monthlyIncome != null ? monthlyIncome : BigDecimal.ZERO)
                    .subtract(monthlyExpense != null ? monthlyExpense : BigDecimal.ZERO));
            
            monthlyStats.add(monthData);
        }
        
        // Reverse the list to show oldest month first
        Collections.reverse(monthlyStats);
        
        return monthlyStats;
    }
}