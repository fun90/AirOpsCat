package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.dto.ClientRequest;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountOnlineIp;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.AccountOnlineIpRepository;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AccountOnlineIpService {

    private final AccountOnlineIpRepository accountOnlineIpRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    
    @Value("${airopscat.online.check-minutes:5}")
    private int checkMinutes;

    @Autowired
    public AccountOnlineIpService(AccountOnlineIpRepository accountOnlineIpRepository, 
                                 AccountRepository accountRepository,
                                 UserRepository userRepository) {
        this.accountOnlineIpRepository = accountOnlineIpRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    /**
     * 处理客户端在线状态更新
     * @param request 客户端请求
     * @param nodeIp 节点IP
     */
    @Transactional
    public void updateOnlineStatus(ClientRequest request, String nodeIp) {
        String accountNo = request.getAccountNo();
        String clientIp = request.getClientIp();
        
        // 使用重试机制处理数据库锁定问题
        int maxRetries = 3;
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            try {
                // 使用 merge 操作替代 find + save，减少数据库操作
                updateOnlineStatusInternal(accountNo, clientIp, nodeIp);
                return; // 成功则退出
            } catch (Exception e) {
                retryCount++;
                if (e.getMessage() != null && e.getMessage().contains("database is locked")) {
                    if (retryCount < maxRetries) {
                        log.warn("Database locked when updating online status for account {}, retrying ({}/{})", 
                                accountNo, retryCount, maxRetries);
                        // 等待一段时间后重试
                        try {
                            Thread.sleep(100 * retryCount); // 递增等待时间
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Update online status interrupted", ie);
                        }
                    } else {
                        log.error("Failed to update online status for account {} after {} retries", accountNo, maxRetries);
                        throw new RuntimeException("Failed to update online status after " + maxRetries + " retries", e);
                    }
                } else {
                    // 非数据库锁定错误，直接抛出
                    log.error("Unexpected error when updating online status for account {}: {}", accountNo, e.getMessage());
                    throw e;
                }
            }
        }
    }
    
    private void updateOnlineStatusInternal(String accountNo, String clientIp, String nodeIp) {
        LocalDateTime now = LocalDateTime.now();
        // 使用传统的 find + save 方式，但配合重试机制
        Optional<AccountOnlineIp> recordOpt = accountOnlineIpRepository.findByAccountNoAndClientIpAndNodeIp(accountNo, clientIp, nodeIp);
        if (recordOpt.isPresent()) {
            AccountOnlineIp record = recordOpt.get();
            record.setLastOnlineTime(now);
            accountOnlineIpRepository.save(record);
        } else {
            AccountOnlineIp newRecord = new AccountOnlineIp();
            newRecord.setAccountNo(accountNo);
            newRecord.setClientIp(clientIp);
            newRecord.setNodeIp(nodeIp);
            newRecord.setLastOnlineTime(now);
            accountOnlineIpRepository.save(newRecord);
        }
    }

    /**
     * 获取指定accountNo的在线记录（只返回在配置时间窗口内的记录）
     */
    public List<AccountOnlineIpDto> getOnlineRecordsByAccountNo(String accountNo) {
        // 计算检查时间范围（当前时间往前推checkMinutes分钟）
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(checkMinutes);
        
        // 直接查询在时间窗口内的记录
        List<AccountOnlineIp> records = accountOnlineIpRepository.findByAccountNoAndLastOnlineTimeAfter(accountNo, checkStartTime);
        return convertToDtoList(records);
    }

    /**
     * 获取指定nodeIp的在线记录（只返回在配置时间窗口内的记录）
     */
    public List<AccountOnlineIpDto> getOnlineRecordsByNodeIp(String nodeIp) {
        // 计算检查时间范围（当前时间往前推checkMinutes分钟）
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(checkMinutes);
        
        // 获取所有记录，然后过滤出在时间窗口内的记录
        List<AccountOnlineIp> allRecords = accountOnlineIpRepository.findByNodeIp(nodeIp);
        List<AccountOnlineIp> validRecords = allRecords.stream()
                .filter(record -> record.getLastOnlineTime() != null && 
                        record.getLastOnlineTime().isAfter(checkStartTime))
                .collect(Collectors.toList());
        
        return convertToDtoList(validRecords);
    }

    /**
     * 获取所有在线记录（只返回在配置时间窗口内的记录）
     */
    public List<AccountOnlineIpDto> getAllOnlineRecords() {
        // 计算检查时间范围（当前时间往前推checkMinutes分钟）
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(checkMinutes);
        
        // 直接查询在时间窗口内的记录
        List<AccountOnlineIp> records = accountOnlineIpRepository.findByLastOnlineTimeAfter(checkStartTime);
        return convertToDtoList(records);
    }

    /**
     * 清理过期的在线记录
     */
    @Transactional
    public void cleanupExpiredRecords() {
        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(checkMinutes * 2); // 清理超过2倍检查时间的记录
        
        // 使用重试机制处理数据库锁定问题
        int maxRetries = 3;
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            try {
                accountOnlineIpRepository.deleteExpiredRecords(expireTime);
                log.info("Successfully cleaned up expired online records before {}", expireTime);
                return; // 成功则退出
            } catch (Exception e) {
                retryCount++;
                if (e.getMessage() != null && e.getMessage().contains("database is locked")) {
                    if (retryCount < maxRetries) {
                        log.warn("Database locked when cleaning up expired records, retrying ({}/{})", 
                                retryCount, maxRetries);
                        // 等待一段时间后重试
                        try {
                            Thread.sleep(200 * retryCount); // 递增等待时间
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Cleanup expired records interrupted", ie);
                        }
                    } else {
                        log.error("Failed to cleanup expired records after {} retries", maxRetries);
                        throw new RuntimeException("Failed to cleanup expired records after " + maxRetries + " retries", e);
                    }
                } else {
                    // 非数据库锁定错误，直接抛出
                    log.error("Unexpected error when cleaning up expired records: {}", e.getMessage());
                    throw e;
                }
            }
        }
    }

    /**
     * 获取检查时间配置
     */
    public int getCheckMinutes() {
        return checkMinutes;
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
        dto.setCreateTime(record.getCreateTime());
        dto.setUpdateTime(record.getUpdateTime());
        
        // 设置关联信息
        Account account = accountMap.get(record.getAccountNo());
        if (account != null) {
            dto.setAccountId(account.getId());
            dto.setUserId(account.getUserId());
            
            User user = userMap.get(account.getUserId());
            if (user != null) {
                dto.setUserNickName(user.getNickName());
            }
        }
        
        return dto;
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
        
        return accountRepository.findByAccountNoIn(accountNos).stream()
                .collect(Collectors.toMap(Account::getAccountNo, account -> account));
    }
    
    /**
     * 获取用户映射
     */
    private Map<Long, User> getUserMap(java.util.Collection<Account> accounts) {
        List<Long> userIds = accounts.stream()
                .map(Account::getUserId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        
        if (userIds.isEmpty()) {
            return Map.of();
        }
        
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
    }
} 