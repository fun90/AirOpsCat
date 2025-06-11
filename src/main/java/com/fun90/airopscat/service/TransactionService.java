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
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final DomainRepository domainRepository;
    private final ServerRepository serverRepository;

    @Autowired
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

    public Page<Transaction> getTransactionPage(int page, int size, String search, Integer type, 
                                         String businessTable, Long businessId,
                                         LocalDateTime startDate, LocalDateTime endDate) {
        // Create pageable with sorting (newest first)
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("transactionDate").descending());

        // Create specification for dynamic filtering
        Specification<Transaction> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Search in description or remark
            if (StringUtils.hasText(search)) {
                Predicate descriptionPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("description")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate remarkPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("remark")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate paymentMethodPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("paymentMethod")),
                        "%" + search.toLowerCase() + "%"
                );
                predicates.add(criteriaBuilder.or(descriptionPredicate, remarkPredicate, paymentMethodPredicate));
            }

            // Filter by type
            if (type != null) {
                predicates.add(criteriaBuilder.equal(root.get("type"), type));
            }

            // Filter by business table and ID
            if (StringUtils.hasText(businessTable)) {
                predicates.add(criteriaBuilder.equal(root.get("businessTable"), businessTable));
            }
            
            if (businessId != null) {
                predicates.add(criteriaBuilder.equal(root.get("businessId"), businessId));
            }

            // Filter by date range
            if (startDate != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("transactionDate"), startDate));
            }
            
            if (endDate != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("transactionDate"), endDate));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return transactionRepository.findAll(spec, pageable);
    }

    public Transaction getTransactionById(Long id) {
        return transactionRepository.findById(id).orElse(null);
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
        BeanUtils.copyProperties(transaction, dto);
        
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
                Optional<Account> account = accountRepository.findById(businessId);
                return account.map(a -> a.getUuid()).orElse("未知账户");
            case "domain":
                Optional<Domain> domain = domainRepository.findById(businessId);
                return domain.map(d -> d.getDomain()).orElse("未知域名");
            case "server":
                Optional<Server> server = serverRepository.findById(businessId);
                return server.map(s -> s.getIp() + (s.getName() != null ? " (" + s.getName() + ")" : "")).orElse("未知服务器");
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
        
        return transactionRepository.save(transaction);
    }

    @Transactional
    public Transaction updateTransaction(Transaction transaction) {
        Transaction existingTransaction = transactionRepository.findById(transaction.getId())
                .orElseThrow(() -> new EntityNotFoundException("Transaction not found"));

        // 使用工具方法复制非null属性
        copyNonNullProperties(transaction, existingTransaction);

        return transactionRepository.save(existingTransaction);
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(Object src, Object target) {
        BeanUtils.copyProperties(src, target, getNullPropertyNames(src));
    }

    // 获取对象中所有为null的属性名
    private String[] getNullPropertyNames(Object source) {
        final BeanWrapper src = new BeanWrapperImpl(source);
        PropertyDescriptor[] pds = src.getPropertyDescriptors();

        Set<String> nullNames = new HashSet<>();
        for (PropertyDescriptor pd : pds) {
            Object srcValue = src.getPropertyValue(pd.getName());
            if (srcValue == null) {
                nullNames.add(pd.getName());
            }
        }
        return nullNames.toArray(new String[0]);
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