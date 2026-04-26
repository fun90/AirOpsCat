package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountDto;
import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.model.enums.PeriodType;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.repository.UserRepository;
import com.fun90.airopscat.service.ratelimit.RateLimitService;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import com.fun90.airopscat.model.entity.AccountTrafficStats;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class AccountService {

    private final AtomicBoolean rateLimitSyncRunning = new AtomicBoolean(false);
    private final AtomicBoolean rateLimitSyncPending = new AtomicBoolean(false);

    @Inject
    AccountRepository accountRepository;
    
    @Inject
    UserRepository userRepository;
    
    @Inject
    AccountTrafficStatsRepository accountTrafficStatsRepository;

    @Inject
    AccountOnlineIpService accountOnlineIpService;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    TagRepository tagRepository;

    @Inject
    NodeRepository nodeRepository;

    @Inject
    ServerRepository serverRepository;

    @Inject
    RateLimitService rateLimitService;

    @Inject
    @Named("blockingTaskExecutor")
    ExecutorService executorService;

    @Inject
    RequestContextController requestContextController;

    @Inject
    TransactionSynchronizationRegistry transactionSynchronizationRegistry;

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
        stats.put("total", accountRepository.count() * systemConfigService.getIntValue("airopscat.account.multiplier", 1));
        stats.put("active", accountRepository.countActiveAccounts(now) * systemConfigService.getIntValue("airopscat.account.multiplier", 1));
        stats.put("expired", accountRepository.countExpiredAccounts(now));
        stats.put("disabled", accountRepository.countDisabledAccounts());
        stats.put("expiringSoon", accountRepository.countExpiringInOneWeek(now, inOneWeek));
        
        // 添加在线用户统计（按accountNo去重）
        long onlineUsers = accountOnlineIpService.getAllOnlineRecords().stream()
                .map(AccountOnlineIpDto::getAccountNo)
                .distinct()
                .count();
        stats.put("onlineUsers", onlineUsers * systemConfigService.getIntValue("airopscat.account.multiplier", 1));
        
        return stats;
    }

    public AccountDto convertToDto(Account account) {
        return convertToDtoList(List.of(account)).getFirst();
    }

    public List<AccountDto> convertToDtoList(List<Account> accounts) {
        if (accounts.isEmpty()) return Collections.emptyList();

        LocalDateTime now = LocalDateTime.now();
        List<Long> accountIds = accounts.stream().map(Account::getId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        List<String> accountNos = accounts.stream().map(Account::getAccountNo).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        List<Long> userIds = accounts.stream().map(Account::getUserId).filter(Objects::nonNull).distinct().collect(Collectors.toList());

        // 批量查询用户信息
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap() :
                userRepository.find("id in ?1", userIds).list().stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        // 批量查询当前周期上传/下载字节数
        Map<Long, long[]> trafficMap = accountTrafficStatsRepository.sumUploadDownloadByAccountIds(accountIds, now);

        // 批量查询当前周期 AccountTrafficStats（用于读取周期配额）
        Map<Long, AccountTrafficStats> currentStatsMap = accountTrafficStatsRepository.findCurrentPeriodByAccountIds(accountIds, now);

        // 批量查询在线连接信息，按 accountNo 分组
        Map<String, List<AccountOnlineIpDto>> onlineByAccountNo = accountNos.isEmpty()
                ? Collections.emptyMap()
                : accountOnlineIpService.getOnlineRecordsByAccountNos(accountNos).stream()
                        .filter(r -> r.getAccountNo() != null)
                        .collect(Collectors.groupingBy(AccountOnlineIpDto::getAccountNo));

        // 批量查询最后在线时间（历史记录兜底）
        Map<String, LocalDateTime> lastOnlineTimeMap = accountOnlineIpService.getLastOnlineTimeMap(accountNos);

        return accounts.stream().map(account -> {
            AccountDto dto = new AccountDto();
            dto.setId(account.getId());
            dto.setUserId(account.getUserId());
            dto.setAccountNo(account.getAccountNo());
            dto.setUuid(account.getUuid());
            dto.setAuthCode(account.getAuthCode());
            dto.setFromDate(account.getFromDate());
            dto.setToDate(account.getToDate());
            dto.setBandwidth(account.getBandwidth());
            dto.setCreateTime(account.getCreateTime());
            dto.setDisabled(account.getDisabled());
            dto.setPeriodType(account.getPeriodType());
            dto.setRemark(account.getRemark());
            dto.setLevel(account.getLevel());
            dto.setNodeMultiple(account.getNodeMultiple());
            dto.setNodePrefix(account.getNodePrefix());
            dto.setMaxConnections(account.getMaxConnections());
            dto.setSpeed(account.getSpeed());

            // 用户信息
            User user = account.getUserId() != null ? userMap.get(account.getUserId()) : null;
            if (user != null) {
                dto.setUserEmail(user.getEmail());
                dto.setNickName(user.getNickName());
            }

            // 流量使用
            long[] traffic = trafficMap.getOrDefault(account.getId(), new long[]{0L, 0L});
            dto.setUsedUploadBytes(traffic[0]);
            dto.setUsedDownloadBytes(traffic[1]);
            dto.setTotalUsedBytes(traffic[0] + traffic[1]);

            // 有效配额：周期配额优先，其次账户基准
            AccountTrafficStats currentStats = currentStatsMap.get(account.getId());
            Long effectiveBandwidth = (currentStats != null && currentStats.getBandwidthQuota() != null)
                    ? currentStats.getBandwidthQuota()
                    : (account.getBandwidth() != null ? account.getBandwidth().longValue() : null);
            dto.setEffectiveBandwidth(effectiveBandwidth);
            if (effectiveBandwidth != null && effectiveBandwidth > 0) {
                long bandwidthInBytes = effectiveBandwidth * 1024L * 1024L * 1024L;
                dto.setUsagePercentage(Math.min(100.0, (dto.getTotalUsedBytes() * 100.0) / bandwidthInBytes));
            } else {
                dto.setUsagePercentage(0.0);
            }

            // 在线连接
            if (account.getAccountNo() != null) {
                List<AccountOnlineIpDto> onlineConnections = onlineByAccountNo.getOrDefault(account.getAccountNo(), Collections.emptyList());
                dto.setOnlineConnections(onlineConnections);
                dto.setOnlineConnectionCount(onlineConnections.size());

                // 最后在线时间：优先当前在线记录中最新的，否则取历史最大值
                if (!onlineConnections.isEmpty()) {
                    dto.setLastOnlineTime(onlineConnections.stream()
                            .map(AccountOnlineIpDto::getLastOnlineTime)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElse(null));
                } else {
                    dto.setLastOnlineTime(lastOnlineTimeMap.get(account.getAccountNo()));
                }
            }

            // 到期剩余天数
            if (account.getToDate() != null) {
                dto.setDaysUntilExpiration(ChronoUnit.DAYS.between(now, account.getToDate()));
            }

            return dto;
        }).collect(Collectors.toList());
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
        triggerRateLimitSync();
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

        triggerRateLimitSync();

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
        triggerRateLimitSync();
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

    private void triggerRateLimitSync() {
        transactionSynchronizationRegistry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                // no-op
            }

            @Override
            public void afterCompletion(int status) {
                if (status != Status.STATUS_COMMITTED) {
                    return;
                }
                scheduleRateLimitSync();
            }
        });
    }

    private void scheduleRateLimitSync() {
        rateLimitSyncPending.set(true);
        if (!rateLimitSyncRunning.compareAndSet(false, true)) {
            log.info("限速同步任务已在队列或执行中，本次请求已合并");
            return;
        }

        CompletableFuture.runAsync(() -> {
            boolean activated = requestContextController.activate();
            try {
                while (true) {
                    rateLimitSyncPending.set(false);
                    rateLimitService.syncAll();
                    if (!rateLimitSyncPending.get()) {
                        break;
                    }
                    log.info("检测到新的限速同步请求，继续执行下一轮入口合并同步");
                }
            } catch (Exception e) {
                log.error("同步限速配置文件失败", e);
            } finally {
                if (activated) {
                    requestContextController.deactivate();
                }
                rateLimitSyncRunning.set(false);
                if (rateLimitSyncPending.get() && rateLimitSyncRunning.compareAndSet(false, true)) {
                    CompletableFuture.runAsync(this::runScheduledRateLimitSync, executorService);
                }
            }
        }, executorService);
    }

    private void runScheduledRateLimitSync() {
        boolean activated = requestContextController.activate();
        try {
            while (true) {
                rateLimitSyncPending.set(false);
                rateLimitService.syncAll();
                if (!rateLimitSyncPending.get()) {
                    break;
                }
                log.info("收尾阶段检测到新的限速同步请求，继续执行下一轮入口合并同步");
            }
        } catch (Exception e) {
            log.error("同步限速配置文件失败", e);
        } finally {
            if (activated) {
                requestContextController.deactivate();
            }
            rateLimitSyncRunning.set(false);
            if (rateLimitSyncPending.get()) {
                scheduleRateLimitSync();
            }
        }
    }

}
