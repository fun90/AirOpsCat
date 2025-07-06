package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountTrafficStatsDto;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.UserRepository;
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

import java.beans.PropertyDescriptor;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AccountTrafficStatsService {

    private final AccountTrafficStatsRepository accountTrafficStatsRepository;
    private final UserRepository userRepository;

    @Autowired
    public AccountTrafficStatsService(AccountTrafficStatsRepository accountTrafficStatsRepository, 
                                     UserRepository userRepository) {
        this.accountTrafficStatsRepository = accountTrafficStatsRepository;
        this.userRepository = userRepository;
    }

    public Page<AccountTrafficStats> getStatsPage(int page, int size, String search, Long userId, Long accountId, 
                                                LocalDateTime startDate, LocalDateTime endDate) {
        // Create pageable with sorting (newest first by periodEnd)
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("periodEnd").descending());

        // Create specification for dynamic filtering
        Specification<AccountTrafficStats> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (userId != null) {
                predicates.add(criteriaBuilder.equal(root.get("userId"), userId));
            }

            if (accountId != null) {
                predicates.add(criteriaBuilder.equal(root.get("accountId"), accountId));
            }

            if (startDate != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("periodStart"), startDate));
            }
            
            if (endDate != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("periodEnd"), endDate));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return accountTrafficStatsRepository.findAll(spec, pageable);
    }

    public AccountTrafficStats getStatsById(Long id) {
        return accountTrafficStatsRepository.findById(id).orElse(null);
    }

    public List<AccountTrafficStats> getStatsByUser(Long userId) {
        return accountTrafficStatsRepository.findByUserId(userId);
    }

    public List<AccountTrafficStats> getStatsByAccount(Long accountId) {
        return accountTrafficStatsRepository.findByAccountId(accountId);
    }

    public List<AccountTrafficStats> getStatsByUserAndPeriod(Long userId, LocalDateTime startDate, LocalDateTime endDate) {
        return accountTrafficStatsRepository.findByUserIdAndPeriod(userId, startDate, endDate);
    }

    public List<AccountTrafficStats> getStatsByAccountAndPeriod(Long accountId, LocalDateTime startDate, LocalDateTime endDate) {
        return accountTrafficStatsRepository.findByAccountIdAndPeriod(accountId, startDate, endDate);
    }

    public List<AccountTrafficStats> getStatsByAccountAndCurrentTime(Long accountId, LocalDateTime currentTime) {
        return accountTrafficStatsRepository.findByAccountIdAndCurrentTime(accountId, currentTime);
    }

    public Long getTotalUploadByUser(Long userId) {
        Long sum = accountTrafficStatsRepository.sumUploadBytesByUserId(userId);
        return sum != null ? sum : 0L;
    }

    public Long getTotalDownloadByUser(Long userId) {
        Long sum = accountTrafficStatsRepository.sumDownloadBytesByUserId(userId);
        return sum != null ? sum : 0L;
    }

    public Long getTotalUploadByAccount(Long accountId) {
        Long sum = accountTrafficStatsRepository.sumUploadBytesByAccountId(accountId);
        return sum != null ? sum : 0L;
    }

    public Long getTotalDownloadByAccount(Long accountId) {
        Long sum = accountTrafficStatsRepository.sumDownloadBytesByAccountId(accountId);
        return sum != null ? sum : 0L;
    }

    public AccountTrafficStatsDto convertToDto(AccountTrafficStats stats) {
        AccountTrafficStatsDto dto = new AccountTrafficStatsDto();
        BeanUtils.copyProperties(stats, dto);
        dto.setTotalBytes(stats.getUploadBytes() + stats.getDownloadBytes());
        
        // Enrich with user email if available
        if (stats.getUserId() != null) {
            Optional<User> userOpt = userRepository.findById(stats.getUserId());
            userOpt.ifPresent(user -> dto.setUserEmail(user.getEmail()));
        }
        
        return dto;
    }

    @Transactional
    public AccountTrafficStats saveStats(AccountTrafficStats stats) {
        // Ensure user exists
        if (stats.getUserId() != null && !userRepository.existsById(stats.getUserId())) {
            throw new EntityNotFoundException("User with ID " + stats.getUserId() + " not found");
        }
        
        return accountTrafficStatsRepository.save(stats);
    }

    @Transactional
    public AccountTrafficStats updateStats(AccountTrafficStats stats) {
        AccountTrafficStats existingStats = accountTrafficStatsRepository.findById(stats.getId())
                .orElseThrow(() -> new EntityNotFoundException("Traffic stats not found"));

        // 使用工具方法复制非null属性
        copyNonNullProperties(stats, existingStats);

        return accountTrafficStatsRepository.save(existingStats);
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
    public void deleteStats(Long id) {
        accountTrafficStatsRepository.deleteById(id);
    }
    
    /**
     * 智能保存或更新流量统计
     * 如果在当前时间段内已有记录，则累加流量；否则创建新记录
     */
    @Transactional
    public AccountTrafficStats saveOrUpdateTrafficStats(Long accountId, Long userId, String periodType, 
                                                       long uploadBytes, long downloadBytes) {
        LocalDateTime currentTime = LocalDateTime.now();
        
        // 查找当前时间段内的流量统计记录
        List<AccountTrafficStats> existingStats = getStatsByAccountAndCurrentTime(accountId, currentTime);
        
        if (!existingStats.isEmpty()) {
            // 如果找到记录，累加流量数据
            AccountTrafficStats stats = existingStats.get(0);
            stats.setUploadBytes(stats.getUploadBytes() + uploadBytes);
            stats.setDownloadBytes(stats.getDownloadBytes() + downloadBytes);
            return accountTrafficStatsRepository.save(stats);
        } else {
            // 如果没有找到记录，创建新记录
            AccountTrafficStats newStats = new AccountTrafficStats();
            newStats.setUserId(userId);
            newStats.setAccountId(accountId);
            newStats.setPeriodStart(currentTime);
            newStats.setPeriodEnd(calculatePeriodEnd(currentTime, periodType));
            newStats.setUploadBytes(uploadBytes);
            newStats.setDownloadBytes(downloadBytes);
            return accountTrafficStatsRepository.save(newStats);
        }
    }
    
    /**
     * 根据统计周期类型计算周期结束时间
     */
    private LocalDateTime calculatePeriodEnd(LocalDateTime periodStart, String periodType) {
        switch (periodType.toUpperCase()) {
            case "MONTHLY":
                // 月周期：从当前时间开始，1个月后
                return periodStart.plusMonths(1).minusNanos(1);
            case "YEARLY":
                // 年周期：从当前时间开始，1年后
                return periodStart.plusYears(1).minusNanos(1);
            default:
                // 默认使用月周期
                return periodStart.plusMonths(1).minusNanos(1);
        }
    }

    // 格式化流量大小 (B, KB, MB, GB)
    public String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}