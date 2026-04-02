package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.dto.ClientRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountOnlineIp;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.AccountOnlineIpRepository;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
@Slf4j
public class AccountOnlineIpService {
    private static final int DEFAULT_CLEANUP_BATCH_SIZE = 1000;

    private final AccountOnlineIpRepository accountOnlineIpRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final SystemConfigService systemConfigService;

    @Inject
    public AccountOnlineIpService(AccountOnlineIpRepository accountOnlineIpRepository,
                                 AccountRepository accountRepository,
                                 UserRepository userRepository,
                                 SystemConfigService systemConfigService) {
        this.accountOnlineIpRepository = accountOnlineIpRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.systemConfigService = systemConfigService;
    }

    /**
     * 处理客户端在线状态更新
     * 使用原子的 INSERT ... ON CONFLICT 操作，解决并发锁定问题
     * @param request 客户端请求
     * @param nodeIp 节点IP
     */
    @Transactional
    public void updateOnlineStatus(ClientRequest request, String nodeIp) {
        String accountNo = request.getAccountNo();
        String clientIp = request.getClientIp();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime offlineThresholdTime = now.minusMinutes(getCheckMinutes());
        
        try {
            accountOnlineIpRepository.upsertOnlineStatus(accountNo, clientIp, nodeIp, now, now, now, now, offlineThresholdTime);
        } catch (Exception e) {
            log.error("Failed to update online status for account {}: {}", accountNo, e.getMessage());
            throw new RuntimeException("Failed to update online status for account " + accountNo, e);
        }
    }

    /**
     * 批量处理客户端在线状态更新
     *
     * @param requests 客户端请求列表
     * @param nodeIp 节点IP
     */
    @Transactional
    public void updateOnlineStatus(List<ClientRequest> requests, String nodeIp) {
        requests.stream()
            .filter(Objects::nonNull)
            .filter(request -> request.getAccountNo() != null && request.getClientIp() != null)
            .forEach(request -> updateOnlineStatus(request, nodeIp));
    }

    /**
     * 获取指定accountNo的在线记录（只返回在配置时间窗口内的记录）
     */
    public List<AccountOnlineIpDto> getOnlineRecordsByAccountNo(String accountNo) {
        // 计算检查时间范围（当前时间往前推checkMinutes分钟）
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(getCheckMinutes());
        
        // 直接查询在时间窗口内的记录
        List<AccountOnlineIp> records = accountOnlineIpRepository.findByAccountNoAndLastOnlineTimeAfter(accountNo, checkStartTime);
        return convertToDtoList(records);
    }

    /**
     * 获取指定nodeIp的在线记录（只返回在配置时间窗口内的记录）
     */
    public List<AccountOnlineIpDto> getOnlineRecordsByNodeIp(String nodeIp) {
        // 计算检查时间范围（当前时间往前推checkMinutes分钟）
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(getCheckMinutes());

        // 获取所有记录，然后过滤出在时间窗口内的记录
        List<AccountOnlineIp> records = accountOnlineIpRepository.findByNodeIpAndLastOnlineTimeAfter(nodeIp, checkStartTime);
        return convertToDtoList(records);
    }

    /**
     * 获取指定服务器IP的在线账户
     */
    public List<AccountOnlineIpDto> getOnlineAccountsByServerIp(String serverIp) {
        return getOnlineRecordsByNodeIp(serverIp);
    }

    /**
     * 获取所有在线记录（只返回在配置时间窗口内的记录）
     */
    public List<AccountOnlineIpDto> getAllOnlineRecords() {
        // 计算检查时间范围（当前时间往前推checkMinutes分钟）
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(getCheckMinutes());
        
        // 直接查询在时间窗口内的记录
        List<AccountOnlineIp> records = accountOnlineIpRepository.findByLastOnlineTimeAfter(checkStartTime);
        return convertToDtoList(records);
    }

    public Map<String, Long> countOnlineRecordsByNodeIps(List<String> nodeIps) {
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(getCheckMinutes());
        return accountOnlineIpRepository.countByNodeIpsAndLastOnlineTimeAfter(nodeIps, checkStartTime);
    }

    /**
     * 批量获取各accountNo的最近一次在线时间
     */
    public Map<String, LocalDateTime> getLastOnlineTimeMap(List<String> accountNos) {
        return accountOnlineIpRepository.findMaxLastOnlineTimeMapByAccountNos(accountNos);
    }

    /**
     * 获取指定accountNo最近一次在线时间（不限时间范围）
     */
    public LocalDateTime getLastOnlineTime(String accountNo) {
        return accountOnlineIpRepository.findMaxLastOnlineTimeByAccountNo(accountNo);
    }

    @Transactional
    public long deleteByAccountNo(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            return 0L;
        }
        return accountOnlineIpRepository.deleteByAccountNo(accountNo);
    }

    /**
     * 清理过期的在线记录
     */
    @Transactional
    public long cleanupExpiredRecords() {
        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(getCheckMinutes() * 2L); // 清理超过2倍检查时间的记录
        int batchSize = getCleanupBatchSize();
        long totalDeleted = 0L;
        int rounds = 0;

        try {
            while (true) {
                int deleted = accountOnlineIpRepository.deleteExpiredRecordsBatch(expireTime, batchSize);
                if (deleted <= 0) {
                    break;
                }
                totalDeleted += deleted;
                rounds++;
                if (deleted < batchSize) {
                    break;
                }
            }
            log.info("在线记录清理完成，截止时间: {}, 批大小: {}, 批次数: {}, 删除总数: {}",
                    expireTime, batchSize, rounds, totalDeleted);
            return totalDeleted;
        } catch (Exception e) {
            log.error("清理在线记录失败，截止时间: {}, 批大小: {}, 已删除: {}",
                    expireTime, batchSize, totalDeleted, e);
            throw new RuntimeException("Failed to cleanup expired records", e);
        }
    }
    
    /**
     * 将实体列表转换为DTO列表
     */
    private List<AccountOnlineIpDto> convertToDtoList(List<AccountOnlineIp> records) {
        // 获取所有相关的账户和用户信息
        Map<String, Account> accountMap = getAccountMap(records);
        Map<Long, User> userMap = getUserMap(accountMap.values());
        
        return records.stream()
                .map(record -> convertToDto(record, accountMap, userMap))
                .collect(Collectors.toList());
    }
    
    /**
     * 将单个实体转换为DTO
     */
    private AccountOnlineIpDto convertToDto(AccountOnlineIp record, Map<String, Account> accountMap, Map<Long, User> userMap) {
        AccountOnlineIpDto dto = new AccountOnlineIpDto();
        dto.setId(record.getId());
        dto.setAccountNo(record.getAccountNo());
        dto.setClientIp(record.getClientIp());
        dto.setNodeIp(record.getNodeIp());
        dto.setLastOnlineTime(record.getLastOnlineTime());
        dto.setSessionStartTime(resolveSessionStartTime(record));
        dto.setCreateTime(record.getCreateTime());
        dto.setUpdateTime(record.getUpdateTime());

        // 设置关联信息
        Account account = accountMap.get(record.getAccountNo());
        if (account != null) {
            dto.setAccountId(account.getId());
            dto.setUserId(account.getUserId());
            dto.setRemark(account.getRemark());

            User user = userMap.get(account.getUserId());
            if (user != null) {
                dto.setUserNickName(user.getNickName());
            }
        }

        return dto;
    }

    private int getCleanupBatchSize() {
        return Math.max(systemConfigService.getIntValue("airopscat.account.online.cleanup.batch-size",
                DEFAULT_CLEANUP_BATCH_SIZE), 1);
    }

    private LocalDateTime resolveSessionStartTime(AccountOnlineIp record) {
        if (record.getSessionStartTime() != null) {
            return record.getSessionStartTime();
        }
        if (record.getCreateTime() != null) {
            return record.getCreateTime();
        }
        return record.getLastOnlineTime();
    }
    
    /**
     * 获取账户映射
     */
    private Map<String, Account> getAccountMap(List<AccountOnlineIp> records) {
        List<String> accountNos = records.stream()
                .map(AccountOnlineIp::getAccountNo)
                .distinct()
                .collect(Collectors.toList());
        
        if (accountNos.isEmpty()) {
            return Map.of();
        }
        
        return accountRepository.list("accountNo in ?1", accountNos).stream()
                .collect(Collectors.toMap(Account::getAccountNo, account -> account));
    }
    
    /**
     * 获取用户映射
     */
    private Map<Long, User> getUserMap(java.util.Collection<Account> accounts) {
        List<Long> userIds = accounts.stream()
                .map(Account::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        
        if (userIds.isEmpty()) {
            return Map.of();
        }
        
        return userRepository.list("id in ?1", userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
    }

    private int getCheckMinutes() {
        return Math.max(1, systemConfigService.getIntValue("airopscat.online.check-minutes", 5));
    }
} 
