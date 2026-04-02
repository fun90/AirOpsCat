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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Locale;

@ApplicationScoped
public class AccountService {

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

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Account> getAccountPage(String search, Long userId, String status, String onlineStatus) {
        // Create sort by createTime descending
        Sort sort = Sort.by("createTime").descending();
        
        // Build query string
        Map<String, Object> params = new HashMap<>();
        
        List<String> conditions = new ArrayList<>();
        
        // Search condition
        if (search != null && !search.trim().isEmpty()) {
            appendSearchConditions(conditions, params, search);
        }
        
        // UserId filter
        if (userId != null) {
            conditions.add("userId = :userId");
            params.put("userId", userId);
        }
        
        // Status filter
        if (status != null && !status.trim().isEmpty()) {
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
                default:
                    break;
            }
        }

        if (onlineStatus != null && !onlineStatus.trim().isEmpty()) {
            Set<String> onlineAccountNos = accountOnlineIpService.getAllOnlineRecords().stream()
                    .map(AccountOnlineIpDto::getAccountNo)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            switch (onlineStatus.toLowerCase()) {
                case "online":
                    if (onlineAccountNos.isEmpty()) {
                        conditions.add("accountNo in :onlineAccountNos");
                        params.put("onlineAccountNos", Collections.singleton("__no_online_accounts__"));
                    } else {
                        conditions.add("accountNo in :onlineAccountNos");
                        params.put("onlineAccountNos", onlineAccountNos);
                    }
                    break;
                case "offline":
                    if (!onlineAccountNos.isEmpty()) {
                        conditions.add("accountNo not in :onlineAccountNos");
                        params.put("onlineAccountNos", onlineAccountNos);
                    }
                    break;
                default:
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

    private void appendSearchConditions(List<String> conditions, Map<String, Object> params, String search) {
        String keyword = normalizeKeyword(search);
        if (keyword == null) {
            return;
        }

        List<String> searchConditions = new ArrayList<>();
        searchConditions.add("accountNo = :searchExact");
        searchConditions.add("accountNo like :searchPrefix");
        searchConditions.add("remark like :searchContains");
        searchConditions.add("uuid = :searchExact");
        searchConditions.add("uuid like :searchPrefix");

        List<Long> matchedUserIds = userRepository.findIdsByKeyword(keyword, 200);
        if (!matchedUserIds.isEmpty()) {
            searchConditions.add("userId in :matchedUserIds");
            params.put("matchedUserIds", matchedUserIds);
        }

        if (isUUID(keyword)) {
            searchConditions.add("uuid like :searchContains");
        }

        conditions.add("(" + String.join(" or ", searchConditions) + ")");
        params.put("searchExact", keyword);
        params.put("searchPrefix", keyword + "%");
        params.put("searchContains", "%" + keyword + "%");
    }

    private String normalizeKeyword(String search) {
        if (search == null) {
            return null;
        }
        String keyword = search.trim();
        return keyword.isEmpty() ? null : keyword.toLowerCase(Locale.ROOT);
    }

    private boolean isUUID(String uuidString) {
        if (uuidString == null) return false;
        try {
            UUID.fromString(uuidString);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public Account getAccountById(Long id) {
        return accountRepository.findById(id);
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
                .map(AccountOnlineIpDto::getAccountNo)
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
        dto.setLevel(account.getLevel());
        dto.setNodeMultiple(account.getNodeMultiple());
        dto.setNodePrefix(account.getNodePrefix());
        dto.setMaxOnlineIps(account.getMaxOnlineIps());
        dto.setSpeed(account.getSpeed());
        
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

    /**
     * 批量转换账户列表为DTO，最后在线时间使用批量查询避免N+1
     */
    public List<AccountDto> convertToDtoList(List<Account> accounts) {
        // 批量查询所有账号的最后在线时间
        List<String> accountNos = accounts.stream()
                .map(Account::getAccountNo)
                .filter(Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        Map<String, LocalDateTime> lastOnlineTimeMap = accountOnlineIpService.getLastOnlineTimeMap(accountNos);

        return accounts.stream().map(account -> {
            AccountDto dto = convertToDto(account);
            if (account.getAccountNo() != null) {
                // 优先取当前在线记录中最新的时间，否则取历史最大值
                if (dto.getOnlineIps() != null && !dto.getOnlineIps().isEmpty()) {
                    LocalDateTime latestOnline = dto.getOnlineIps().stream()
                            .map(AccountOnlineIpDto::getLastOnlineTime)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);
                    dto.setLastOnlineTime(latestOnline);
                } else {
                    dto.setLastOnlineTime(lastOnlineTimeMap.get(account.getAccountNo()));
                }
            }
            return dto;
        }).collect(java.util.stream.Collectors.toList());
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
        copyNonNullProperties(account, existingAccount);

        accountRepository.persist(existingAccount);

        // No need to call save/persist for updates in Panache
        return existingAccount;
    }


    @Transactional
    public void deleteAccount(Long id) {
        Account account = accountRepository.findById(id);
        if (account == null) {
            return;
        }
        accountOnlineIpService.deleteByAccountNo(account.getAccountNo());
        accountTrafficStatsRepository.deleteByAccountId(id);
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
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 32);
    }
    
    // 生成随机账号
    private String generateAccountNo() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12);
    }

    /**
     * 复制源对象的非null属性到目标对象
     * Copy non-null properties from source to target object using reflection
     */
    private void copyNonNullProperties(Account source, Account target) {
        Field[] fields = Account.class.getDeclaredFields();
        for (Field field : fields) {
            try {
                field.setAccessible(true);
                Object value = field.get(source);
                if (value != null) {
                    // 特殊处理 user 属性，设置 userId 而不是整个 user 对象
                    if ("user".equals(field.getName()) && source.getUser() != null) {
                        target.setUserId(source.getUserId());
                    } else if (!"user".equals(field.getName()) && !"id".equals(field.getName())) {
                        // 跳过 id 和 user 字段，id 不应被更新，user 已特殊处理
                        field.set(target, value);
                    }
                }
            } catch (IllegalAccessException e) {
                // 忽略无法访问的字段
            }
        }
    }

}
