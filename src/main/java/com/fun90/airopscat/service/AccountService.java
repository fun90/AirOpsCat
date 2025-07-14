package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountDto;
import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.model.enums.PeriodType;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.UserRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@ApplicationScoped
public class AccountService {

    @ConfigProperty(name = "airopscat.subscription.url", defaultValue = "https://example.com")
    String subscriptionUrl;

    @ConfigProperty(name = "airopscat.account.multiplier", defaultValue = "1")
    Integer accountMultiplier;


    @Inject
    AccountRepository accountRepository;
    
    @Inject
    UserRepository userRepository;
    
    @Inject
    AccountTrafficStatsRepository accountTrafficStatsRepository;
    
    @Inject
    AccountOnlineIpService accountOnlineIpService;

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Account> getAccountPage(String search, Long userId, String status) {
        // Create sort by createTime descending
        Sort sort = Sort.by("createTime").descending();
        
        // Build query string
        StringBuilder queryBuilder = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        
        List<String> conditions = new ArrayList<>();
        
        // Search condition
        if (StringUtils.isNotBlank(search)) {
            conditions.add("(lower(accountNo) like :search or lower(user.email) like :search or lower(user.nickName) like :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
        
        // UserId filter
        if (userId != null) {
            conditions.add("userId = :userId");
            params.put("userId", userId);
        }
        
        // Status filter
        if (StringUtils.isNotBlank(status)) {
            LocalDateTime now = LocalDateTime.now();
            switch (status.toLowerCase()) {
                case "active":
                    conditions.add("disabled = 0 and (toDate is null or toDate >= :now)");
                    params.put("now", now);
                    break;
                case "expired":
                    conditions.add("disabled = 0 and toDate is not null and toDate < :now");
                    params.put("now", now);
                    break;
                case "disabled":
                    conditions.add("disabled = 1");
                    break;
                case "expiring":
                    LocalDateTime sevenDaysLater = now.plusDays(7);
                    conditions.add("disabled = 0 and toDate is not null and toDate > :now and toDate <= :sevenDaysLater");
                    params.put("now", now);
                    params.put("sevenDaysLater", sevenDaysLater);
                    break;
            }
        }
        
        String query = conditions.isEmpty() ? "" : String.join(" and ", conditions);
        
        if (query.isEmpty()) {
            return accountRepository.findAll(sort);
        } else {
            return accountRepository.find(query, sort, params);
        }
    }

    public Account getAccountById(Long id) {
        return accountRepository.findById(id);
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
        stats.put("total", accountRepository.count() * accountMultiplier);
        stats.put("active", accountRepository.countActiveAccounts(now) * accountMultiplier);
        stats.put("expired", accountRepository.countExpiredAccounts(now));
        stats.put("disabled", accountRepository.countDisabledAccounts());
        stats.put("expiringSoon", accountRepository.countExpiringInOneWeek(now, inOneWeek));
        
        // 添加在线用户统计（按accountNo去重）
        long onlineUsers = accountOnlineIpService.getAllOnlineRecords().stream()
                .map(ip -> ip.getAccountNo())
                .distinct()
                .count();
        stats.put("onlineUsers", onlineUsers * accountMultiplier);
        
        return stats;
    }

    public AccountDto convertToDto(Account account) {
        AccountDto dto = new AccountDto();
        // Copy properties manually since BeanUtils is not available
        dto.setId(account.getId());
        dto.setUserId(account.getUserId());
        dto.setAccountNo(account.getAccountNo());
        dto.setUuid(account.getUuid());
        dto.setAuthCode(account.getAuthCode());
        dto.setFromDate(account.getFromDate());
        dto.setToDate(account.getToDate());
        dto.setBandwidth(account.getBandwidth());
        dto.setCreateTime(account.getCreateTime());
        // dto.setUpdateTime(account.getUpdateTime()); // Remove if DTO doesn't have this property
        dto.setDisabled(account.getDisabled());
        dto.setPeriodType(account.getPeriodType());
        dto.setRemark(account.getRemark());
        
        // Enrich with user email if available
        if (account.getUserId() != null) {
            User user = userRepository.findById(account.getUserId());
            if (user != null) {
                dto.setUserEmail(user.getEmail());
                dto.setNickName(user.getNickName());
            }
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
        
        // Generate account number if not provided
        if (account.getAccountNo() == null || account.getAccountNo().trim().isEmpty()) {
            account.setAccountNo(generateAccountNo());
        }
        
        // Ensure user exists
        if (account.getUserId() != null && userRepository.findById(account.getUserId()) == null) {
            throw new EntityNotFoundException("User with ID " + account.getUserId() + " not found");
        }
        
        // Set default values if not provided
        if (account.getDisabled() == null) {
            account.setDisabled(0);
        }
        
        if (account.getPeriodType() == null || account.getPeriodType().trim().isEmpty()) {
            account.setPeriodType(PeriodType.MONTHLY.name());
        }
        
        accountRepository.persist(account);
        return account;
    }

    @Transactional
    public Account updateAccount(Account account) {
        Account existingAccount = accountRepository.findById(account.getId());
        if (existingAccount == null) {
            throw new EntityNotFoundException("Account not found");
        }

        // 使用工具方法复制非null属性
        // Copy non-null properties manually
        if (account.getAccountNo() != null) existingAccount.setAccountNo(account.getAccountNo());
        if (account.getUuid() != null) existingAccount.setUuid(account.getUuid());
        if (account.getAuthCode() != null) existingAccount.setAuthCode(account.getAuthCode());
        if (account.getFromDate() != null) existingAccount.setFromDate(account.getFromDate());
        if (account.getToDate() != null) existingAccount.setToDate(account.getToDate());
        if (account.getBandwidth() != null) existingAccount.setBandwidth(account.getBandwidth());
        if (account.getDisabled() != null) existingAccount.setDisabled(account.getDisabled());
        if (account.getPeriodType() != null) existingAccount.setPeriodType(account.getPeriodType());
        if (account.getRemark() != null) existingAccount.setRemark(account.getRemark());

        // No need to call save/persist for updates in Panache
        return existingAccount;
    }


    @Transactional
    public void deleteAccount(Long id) {
        accountRepository.deleteById(id);
    }

    @Transactional
    public Account toggleAccountStatus(Long id, boolean disabled) {
        Account account = accountRepository.findById(id);
        if (account != null) {
            account.setDisabled(disabled ? 1 : 0);
            // No need to call save/persist for updates in Panache
            return account;
        }
        return null;
    }
    
    @Transactional
    public Account renewAccount(Long id, LocalDateTime newExpiryDate) {
        Account account = accountRepository.findById(id);
        if (account == null) {
            throw new EntityNotFoundException("Account not found");
        }
        
        account.setToDate(newExpiryDate);
        if (account.getDisabled() == 1) {
            account.setDisabled(0); // Reactivate account if disabled
        }
        
        // No need to call save/persist for updates in Panache
        return account;
    }
    
    @Transactional
    public Account resetAuthCode(Long id) {
        Account account = accountRepository.findById(id);
        if (account == null) {
            throw new EntityNotFoundException("Account not found");
        }
        
        account.setAuthCode(generateAuthCode());
        // No need to call save/persist for updates in Panache
        return account;
    }
    
    // 生成随机认证码
    private String generateAuthCode() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 16);
    }
    
    // 生成随机账号
    private String generateAccountNo() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12);
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