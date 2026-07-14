package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.dto.guard.GuardOnlineAccountIpReport;
import com.fun90.airopscat.model.dto.guard.GuardOnlineConnectionRefReport;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountOnlineIp;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.repository.AccountOnlineIpRepository;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLTransientException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
@Slf4j
public class AccountOnlineIpService {
    private static final int DEFAULT_CLEANUP_BATCH_SIZE = 1000;
    private static final int UPSERT_MAX_ATTEMPTS = 3;
    private static final long UPSERT_RETRY_BACKOFF_MILLIS = 50L;

    private final AccountOnlineIpRepository accountOnlineIpRepository;
    private final AccountRepository accountRepository;
    private final NodeRepository nodeRepository;
    private final UserRepository userRepository;
    private final SystemConfigService systemConfigService;

    @Inject
    public AccountOnlineIpService(AccountOnlineIpRepository accountOnlineIpRepository,
                                 AccountRepository accountRepository,
                                 NodeRepository nodeRepository,
                                 UserRepository userRepository,
                                 SystemConfigService systemConfigService) {
        this.accountOnlineIpRepository = accountOnlineIpRepository;
        this.accountRepository = accountRepository;
        this.nodeRepository = nodeRepository;
        this.userRepository = userRepository;
        this.systemConfigService = systemConfigService;
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

    public Map<Long, Long> countOnlineRecordsByNodeIds(List<Long> nodeIds) {
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(getCheckMinutes());
        return accountOnlineIpRepository.countByNodeIdsAndLastOnlineTimeAfter(nodeIds, checkStartTime);
    }

    public Map<String, Long> countOnlineRecordsByAccountNos(List<String> accountNos) {
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(getCheckMinutes());
        return accountOnlineIpRepository.countByAccountNosAndLastOnlineTimeAfter(accountNos, checkStartTime);
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
     * 基于 guard-sync 账号在线 IP 聚合记录刷新在线状态。
     *
     * @param nodeIp         节点服务器 IP，写入 node_ip 字段
     * @param onlineAccountIps guard agent 上报的账号在线 IP 聚合记录
     * @return 本轮成功 upsert 的记录数
     */
    public int refreshFromGuardAccountIps(String nodeIp, List<GuardOnlineAccountIpReport> onlineAccountIps) {
        if (nodeIp == null || nodeIp.isBlank() || onlineAccountIps == null) {
            return 0;
        }

        String normalizedNodeIp = nodeIp.trim();
        if (onlineAccountIps.isEmpty()) {
            return 0;
        }

        Set<String> candidateNos = onlineAccountIps.stream()
                .filter(Objects::nonNull)
                .map(GuardOnlineAccountIpReport::getAccountNo)
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toSet());
        Set<String> validAccountNos = candidateNos.isEmpty() ? Set.of()
                : new HashSet<>(accountRepository.findExistingAccountNos(new ArrayList<>(candidateNos)));

        Map<String, Node> nodeByTag = nodeRepository.findOnlineTrackableByServerIp(normalizedNodeIp).stream()
                .collect(Collectors.toMap(Node::getTag, node -> node, (left, right) -> left));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime offlineThreshold = now.minusMinutes(getCheckMinutes());
        Set<String> seen = new HashSet<>();
        int count = 0;

        for (GuardOnlineAccountIpReport report : onlineAccountIps) {
            if (report == null) {
                continue;
            }
            String accountNo = normalizeBlank(report.getAccountNo());
            if (accountNo == null) {
                continue;
            }
            if (!validAccountNos.contains(accountNo)) {
                log.debug("refreshFromGuardAccountIps 跳过无效账号: authUser={}, nodeIp={}", accountNo, normalizedNodeIp);
                continue;
            }
            String nodeTag = normalizeBlank(report.getNodeTag());
            Node node = nodeTag == null ? null : nodeByTag.get(nodeTag);
            if (report.getConnections() != null && !report.getConnections().isEmpty()) {
                count += refreshFromGuardConnectionRefs(report.getConnections(), accountNo, normalizedNodeIp, nodeTag,
                        node, now, offlineThreshold, seen);
                continue;
            }
            List<String> clientIps = report.getClientIps() == null ? List.of() : report.getClientIps();
            for (String rawClientIp : clientIps) {
                String clientIp = normalizeBlank(rawClientIp);
                if (clientIp == null) {
                    continue;
                }
                String dedupeKey = accountNo + "\0" + clientIp + "\0" + normalizedNodeIp + "\0" + Objects.toString(nodeTag, "");
                if (!seen.add(dedupeKey)) {
                    continue;
                }
                String connectionId = buildGuardAccountIpConnectionId(accountNo, clientIp, normalizedNodeIp, nodeTag);
                if (upsertOnlineStatusWithRetry(accountNo, clientIp, connectionId, normalizedNodeIp,
                        node == null ? null : node.getId(), nodeTag, now, now, now, now, offlineThreshold,
                        "refreshFromGuardAccountIps")) {
                    count++;
                }
            }
        }
        return count;
    }

    private int refreshFromGuardConnectionRefs(List<GuardOnlineConnectionRefReport> connections,
                                               String accountNo,
                                               String nodeIp,
                                               String nodeTag,
                                               Node node,
                                               LocalDateTime now,
                                               LocalDateTime offlineThreshold,
                                               Set<String> seen) {
        int count = 0;
        for (GuardOnlineConnectionRefReport connection : connections) {
            if (connection == null) {
                continue;
            }
            String clientIp = normalizeBlank(connection.getClientIp());
            if (clientIp == null) {
                continue;
            }
            String connectionId = buildGuardConnectionRefId(connection, accountNo, clientIp, nodeIp, nodeTag);
            String dedupeKey = accountNo + "\0" + connectionId + "\0" + nodeIp;
            if (!seen.add(dedupeKey)) {
                continue;
            }
            LocalDateTime sessionStartTime = resolveConnectionStartTime(connection.getStart(), now);
            if (upsertOnlineStatusWithRetry(accountNo, clientIp, connectionId, nodeIp,
                    node == null ? null : node.getId(), nodeTag, now, sessionStartTime, now, now, offlineThreshold,
                    "refreshFromGuardConnectionRefs")) {
                count++;
            }
        }
        return count;
    }

    public List<AccountOnlineIpDto> getOnlineRecordsByNodeId(Long nodeId) {
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(getCheckMinutes());
        List<AccountOnlineIp> records = accountOnlineIpRepository.findByNodeIdAndLastOnlineTimeAfter(nodeId, checkStartTime);
        return convertToDtoList(records);
    }

    public List<AccountOnlineIpDto> getOnlineRecordsByAccountNos(List<String> accountNos) {
        LocalDateTime checkStartTime = LocalDateTime.now().minusMinutes(getCheckMinutes());
        List<AccountOnlineIp> records = accountOnlineIpRepository.findByAccountNosAndLastOnlineTimeAfter(accountNos, checkStartTime);
        return convertToDtoList(records);
    }

    private boolean upsertOnlineStatusWithRetry(String accountNo,
                                                String clientIp,
                                                String connectionId,
                                                String nodeIp,
                                                Long nodeId,
                                                String nodeTag,
                                                LocalDateTime lastOnlineTime,
                                                LocalDateTime sessionStartTime,
                                                LocalDateTime createTime,
                                                LocalDateTime updateTime,
                                                LocalDateTime offlineThresholdTime,
                                                String source) {
        for (int attempt = 1; attempt <= UPSERT_MAX_ATTEMPTS; attempt++) {
            try {
                accountOnlineIpRepository.upsertOnlineStatus(accountNo, clientIp, connectionId, nodeIp,
                        nodeId, nodeTag, lastOnlineTime, sessionStartTime, createTime, updateTime, offlineThresholdTime);
                return true;
            } catch (Exception e) {
                if (isRetryableUpsertFailure(e) && attempt < UPSERT_MAX_ATTEMPTS) {
                    log.warn("{} upsert 遇到并发锁冲突，准备重试: accountNo={}, clientIp={}, connectionId={}, nodeIp={}, attempt={}/{}, error={}",
                            source, accountNo, clientIp, connectionId, nodeIp, attempt, UPSERT_MAX_ATTEMPTS, e.getMessage());
                    sleepBeforeRetry(attempt);
                    continue;
                }
                log.error("{} upsert 失败: accountNo={}, clientIp={}, connectionId={}, nodeIp={}, attempt={}/{}",
                        source, accountNo, clientIp, connectionId, nodeIp, attempt, UPSERT_MAX_ATTEMPTS, e);
                return false;
            }
        }
        return false;
    }

    private boolean isRetryableUpsertFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PessimisticLockException
                    || current instanceof LockTimeoutException
                    || current instanceof SQLTransientException) {
                return true;
            }
            String simpleName = current.getClass().getSimpleName();
            if (simpleName.contains("LockAcquisition") || simpleName.contains("TransactionRollback")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase();
                if (lowerMessage.contains("deadlock found")
                        || lowerMessage.contains("try restarting transaction")
                        || lowerMessage.contains("lock wait timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(UPSERT_RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 清理超过指定小时数的历史在线记录
     */
    @Transactional
    public long cleanupOldRecords() {
        int retentionHours = Math.max(1, systemConfigService.getIntValue("airopscat.account.online.history.retention-hours", 8));
        LocalDateTime expireTime = LocalDateTime.now().minusHours(retentionHours);
        int batchSize = getCleanupBatchSize();
        long totalDeleted = 0L;
        int rounds = 0;
        long startedAt = System.nanoTime();
        try {
            while (true) {
                int deleted = accountOnlineIpRepository.deleteExpiredRecordsBatch(expireTime, batchSize);
                if (deleted <= 0) break;
                totalDeleted += deleted;
                rounds++;
                if (deleted < batchSize) break;
            }
            log.info("在线记录历史清理完成，保留小时数={}, 截止时间={}, 批次={}, 删除总数={}, 耗时={} ms",
                    retentionHours, expireTime, rounds, totalDeleted, elapsedMillis(startedAt));
            return totalDeleted;
        } catch (Exception e) {
            log.error("在线记录历史清理失败，截止时间={}, 已删除={}, 耗时={} ms",
                    expireTime, totalDeleted, elapsedMillis(startedAt), e);
            throw new RuntimeException("Failed to cleanup old online records", e);
        }
    }

    /**
     * 清理过期的在线记录（短窗口，基于在线检测分钟数）
     */
    @Transactional
    public long cleanupExpiredRecords() {
        long startedAt = System.nanoTime();
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
            log.info("在线记录清理完成，截止时间: {}, 批大小: {}, 批次数: {}, 删除总数: {}, 耗时: {} ms",
                    expireTime, batchSize, rounds, totalDeleted, elapsedMillis(startedAt));
            return totalDeleted;
        } catch (Exception e) {
            log.error("清理在线记录失败，截止时间: {}, 批大小: {}, 已删除: {}, 耗时: {} ms",
                    expireTime, batchSize, totalDeleted, elapsedMillis(startedAt), e);
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
        Map<Long, Node> nodeMap = getNodeMap(records);

        return records.stream()
                .map(record -> convertToDto(record, accountMap, userMap, nodeMap))
                .collect(Collectors.toList());
    }

    /**
     * 将单个实体转换为DTO
     */
    private AccountOnlineIpDto convertToDto(AccountOnlineIp record,
                                           Map<String, Account> accountMap,
                                           Map<Long, User> userMap,
                                           Map<Long, Node> nodeMap) {
        AccountOnlineIpDto dto = new AccountOnlineIpDto();
        dto.setId(record.getId());
        dto.setAccountNo(record.getAccountNo());
        dto.setClientIp(record.getClientIp());
        dto.setConnectionId(record.getConnectionId());
        dto.setNodeIp(record.getNodeIp());
        dto.setNodeId(record.getNodeId());
        dto.setNodeTag(record.getNodeTag());
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

        Node node = record.getNodeId() == null ? null : nodeMap.get(record.getNodeId());
        if (node != null) {
            dto.setNodeName(formatNodeName(node));
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

    private Map<Long, Node> getNodeMap(List<AccountOnlineIp> records) {
        List<Long> nodeIds = records.stream()
                .map(AccountOnlineIp::getNodeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (nodeIds.isEmpty()) {
            return Map.of();
        }

        return nodeRepository.findByIdIn(nodeIds).stream()
                .collect(Collectors.toMap(Node::getId, node -> node));
    }

    private String formatNodeName(Node node) {
        if (node.getName() != null && !node.getName().isBlank() && node.getNo() != null) {
            return node.getName() + "-" + node.getNo();
        }
        if (node.getName() != null && !node.getName().isBlank()) {
            return node.getName();
        }
        if (node.getNo() != null) {
            return String.valueOf(node.getNo());
        }
        return node.getTag();
    }

    private String buildGuardAccountIpConnectionId(String accountNo,
                                                   String clientIp,
                                                   String nodeIp,
                                                   String nodeTag) {
        return String.join("|",
                "guard-ip",
                accountNo,
                clientIp,
                Objects.toString(nodeIp, ""),
                Objects.toString(nodeTag, ""));
    }

    private String buildGuardConnectionRefId(GuardOnlineConnectionRefReport connection,
                                             String accountNo,
                                             String clientIp,
                                             String nodeIp,
                                             String nodeTag) {
        String rawId = normalizeBlank(connection.getConnectionId());
        if (rawId != null) {
            return rawId;
        }
        return buildGuardAccountIpConnectionId(accountNo, clientIp, nodeIp, nodeTag);
    }

    private LocalDateTime resolveConnectionStartTime(String rawStart, LocalDateTime fallback) {
        String start = normalizeBlank(rawStart);
        if (start == null) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(start).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return java.time.Instant.parse(start).atZone(ZoneId.systemDefault()).toLocalDateTime();
            } catch (DateTimeParseException ignoredAgain) {
                log.debug("无法解析连接开始时间: start={}", start);
                return fallback;
            }
        }
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int getCheckMinutes() {
        return Math.max(1, systemConfigService.getIntValue("airopscat.online.check-minutes", 10));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
