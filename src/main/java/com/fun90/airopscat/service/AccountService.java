package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountDto;
import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.model.enums.PeriodType;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AccountService {

    @Value("${airopscat.subscription.url:https://example.com}")
    private String subscriptionUrl;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountTrafficStatsRepository accountTrafficStatsRepository;
    private final AccountOnlineIpService accountOnlineIpService;

    @Autowired
    public AccountService(
            AccountRepository accountRepository, 
            UserRepository userRepository,
            AccountTrafficStatsRepository accountTrafficStatsRepository,
            AccountOnlineIpService accountOnlineIpService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.accountTrafficStatsRepository = accountTrafficStatsRepository;
        this.accountOnlineIpService = accountOnlineIpService;
    }

    public Page<Account> getAccountPage(int page, int size, String search, Long userId, Boolean expired, Boolean disabled) {
        // Create pageable with sorting (newest first)
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("createTime").descending());

        // Create specification for dynamic filtering
        Specification<Account> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Search in accountNo and associated user's email and nickName
            if (StringUtils.hasText(search)) {
                // Search in accountNo
                Predicate accountNoPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("accountNo")),
                        "%" + search.toLowerCase() + "%"
                );
                
                // Join with User table to search in email and nickName
                Join<Account, User> userJoin = root.join("user", JoinType.LEFT);
                Predicate emailPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(userJoin.get("email")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate nickNamePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(userJoin.get("nickName")),
                        "%" + search.toLowerCase() + "%"
                );
                
                predicates.add(criteriaBuilder.or(accountNoPredicate, emailPredicate, nickNamePredicate));
            }

            // Filter by userId
            if (userId != null) {
                predicates.add(criteriaBuilder.equal(root.get("userId"), userId));
            }

            // Filter by expired status
            LocalDateTime now = LocalDateTime.now();
            if (expired != null) {
                if (expired) {
                    predicates.add(criteriaBuilder.and(
                            criteriaBuilder.isNotNull(root.get("toDate")),
                            criteriaBuilder.lessThan(root.get("toDate"), now)
                    ));
                } else {
                    predicates.add(criteriaBuilder.or(
                            criteriaBuilder.isNull(root.get("toDate")),
                            criteriaBuilder.greaterThanOrEqualTo(root.get("toDate"), now)
                    ));
                }
            }

            // Filter by disabled status
            if (disabled != null) {
                predicates.add(criteriaBuilder.equal(root.get("disabled"), disabled ? 1 : 0));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return accountRepository.findAll(spec, pageable);
    }

    public Account getAccountById(Long id) {
        return accountRepository.findById(id).orElse(null);
    }

    public Optional<Account> getByUuid(String uuid) {
        return accountRepository.findByUuid(uuid);
    }

    public List<Account> getAccountsByUser(Long userId) {
        return accountRepository.findByUserId(userId);
    }

    public List<Account> getExpiringAccounts(int days) {
        LocalDateTime expiryDate = LocalDateTime.now().plusDays(days);
        return accountRepository.findExpiringAccounts(expiryDate);
    }

    public Map<String, Long> getAccountsStats() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime inOneWeek = now.plusWeeks(1);
        
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", accountRepository.count());
        stats.put("active", accountRepository.countActiveAccounts(now));
        stats.put("expired", accountRepository.countExpiredAccounts(now));
        stats.put("disabled", accountRepository.countDisabledAccounts());
        stats.put("expiringSoon", accountRepository.countExpiringInOneWeek(now, inOneWeek));
        
        // 添加在线用户统计（按accountNo去重）
        long onlineUsers = accountOnlineIpService.getAllOnlineRecords().stream()
                .map(ip -> ip.getAccountNo())
                .distinct()
                .count();
        stats.put("onlineUsers", onlineUsers);
        
        return stats;
    }

    public AccountDto convertToDto(Account account) {
        AccountDto dto = new AccountDto();
        BeanUtils.copyProperties(account, dto);
        
        // Enrich with user email if available
        if (account.getUserId() != null) {
            Optional<User> userOpt = userRepository.findById(account.getUserId());
            userOpt.ifPresent(user -> {
                dto.setUserEmail(user.getEmail());
                dto.setNickName(user.getNickName());
            });
        }
        
        // Add traffic usage data
        Long uploadBytes = accountTrafficStatsRepository.sumUploadBytesByAccountId(account.getId());
        Long downloadBytes = accountTrafficStatsRepository.sumDownloadBytesByAccountId(account.getId());
        dto.setUsedUploadBytes(uploadBytes != null ? uploadBytes : 0L);
        dto.setUsedDownloadBytes(downloadBytes != null ? downloadBytes : 0L);
        dto.setTotalUsedBytes(dto.getUsedUploadBytes() + dto.getUsedDownloadBytes());
        
        // Calculate usage percentage if bandwidth is set
        if (account.getBandwidth() != null && account.getBandwidth() > 0) {
            // Convert bandwidth from GB to bytes for comparison (bandwidth is stored in GB)
            long bandwidthInBytes = account.getBandwidth() * 1024L * 1024L * 1024L;
            dto.setUsagePercentage(Math.min(100.0, (dto.getTotalUsedBytes() * 100.0) / bandwidthInBytes));
        } else {
            dto.setUsagePercentage(0.0);
        }
        
        // Add online IP information
        if (account.getAccountNo() != null) {
            List<AccountOnlineIpDto> onlineIps = accountOnlineIpService.getOnlineRecordsByAccountNo(account.getAccountNo());
            dto.setOnlineIps(onlineIps);
        }
        
        // Calculate days until expiration
        if (account.getToDate() != null) {
            LocalDateTime now = LocalDateTime.now();
            dto.setDaysUntilExpiration(ChronoUnit.DAYS.between(now, account.getToDate()));
        } else {
            dto.setDaysUntilExpiration(null);
        }
        
        return dto;
    }

    @Transactional
    public Account saveAccount(Account account) {
        // Generate UUID if not provided
        if (account.getUuid() == null || account.getUuid().trim().isEmpty()) {
            account.setUuid(UUID.randomUUID().toString());
        }
        
        // Generate auth code if not provided
        if (account.getAuthCode() == null || account.getAuthCode().trim().isEmpty()) {
            account.setAuthCode(generateAuthCode());
        }
        
        // Ensure user exists
        if (account.getUserId() != null && !userRepository.existsById(account.getUserId())) {
            throw new EntityNotFoundException("User with ID " + account.getUserId() + " not found");
        }
        
        // Set default values if not provided
        if (account.getDisabled() == null) {
            account.setDisabled(0);
        }
        
        if (account.getPeriodType() == null || account.getPeriodType().trim().isEmpty()) {
            account.setPeriodType(PeriodType.MONTHLY.name());
        }
        
        return accountRepository.save(account);
    }

    @Transactional
    public Account updateAccount(Account account) {
        Account existingAccount = accountRepository.findById(account.getId())
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));

        // 使用工具方法复制非null属性
        copyNonNullProperties(account, existingAccount);

        return accountRepository.save(existingAccount);
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
    public void deleteAccount(Long id) {
        accountRepository.deleteById(id);
    }

    @Transactional
    public Account toggleAccountStatus(Long id, boolean disabled) {
        Optional<Account> optionalAccount = accountRepository.findById(id);
        if (optionalAccount.isPresent()) {
            Account account = optionalAccount.get();
            account.setDisabled(disabled ? 1 : 0);
            return accountRepository.save(account);
        }
        return null;
    }
    
    @Transactional
    public Account renewAccount(Long id, LocalDateTime newExpiryDate) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));
        
        account.setToDate(newExpiryDate);
        if (account.getDisabled() == 1) {
            account.setDisabled(0); // Reactivate account if disabled
        }
        
        return accountRepository.save(account);
    }
    
    @Transactional
    public Account resetAuthCode(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Account not found"));
        
        account.setAuthCode(generateAuthCode());
        return accountRepository.save(account);
    }
    
    // 生成随机认证码
    private String generateAuthCode() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 16);
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
    
    // 获取配置URL
    public String getConfigUrl(Account account, String osName, String appName) {
        if (account == null || account.getUuid() == null) {
            return null;
        }
        return subscriptionUrl + "/config/" + account.getAuthCode() + "/" + osName + "/" + appName;
    }
    
    // 获取指定用户的账户数量
    public Long countAccountsByUser(Long userId) {
        return accountRepository.countByUserId(userId);
    }
}