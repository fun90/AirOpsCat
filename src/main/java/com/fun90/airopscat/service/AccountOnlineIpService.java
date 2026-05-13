package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.dto.ClientRequest;
import com.fun90.airopscat.model.dto.singbox.SingBoxConnectionSnapshot;
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
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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
            accountOnlineIpRepository.upsertOnlineStatus(accountNo, clientIp, legacyConnectionId(clientIp, nodeIp),
                    nodeIp, null, null, now, now, now, now, offlineThresholdTime);
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
     * 基于 Clash API 连接列表批量刷新在线状态
     *
     * @param serverIp    服务器 IP，写入 node_ip 字段
     * @param connections Clash API 返回的连接快照列表
     * @return 本轮成功 upsert 的记录数
     */
    @Transactional
    public int refreshFromConnections(String serverIp, List<SingBoxConnectionSnapshot> connections) {
        return refreshFromConnections(serverIp, connections, Map.of());
    }

    @Transactional
    public int refreshFromConnections(String serverIp, List<SingBoxConnectionSnapshot> connections, Map<String, Node> nodeByTag) {
        if (connections == null || connections.isEmpty()) {
            return 0;
        }

        // 预先收集所有 authUser，批量校验账号是否存在，过滤落地节点公共账号等无效用户
        Set<String> candidateNos = connections.stream()
                .filter(c -> c.getMetadata() != null)
                .map(c -> c.getMetadata().getAuthUser())
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toSet());
        Set<String> validAccountNos = candidateNos.isEmpty() ? Set.of()
                : new HashSet<>(accountRepository.findExistingAccountNos(new java.util.ArrayList<>(candidateNos)));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime offlineThreshold = now.minusMinutes(getCheckMinutes());
        Set<String> seen = new HashSet<>();
        int count = 0;

        for (SingBoxConnectionSnapshot conn : connections) {
            if (conn.getMetadata() == null) {
                continue;
            }
            String accountNo = conn.getMetadata().getAuthUser();
            String clientIp = conn.getMetadata().getSourceIP();
            if (accountNo == null || accountNo.isBlank() || clientIp == null || clientIp.isBlank()) {
                continue;
            }
            if (!validAccountNos.contains(accountNo)) {
                log.debug("refreshFromConnections 跳过无效账号: authUser={}, serverIp={}", accountNo, serverIp);
                continue;
            }
            String nodeTag = normalizeBlank(conn.getMetadata().resolveNodeTag());
            Node node = nodeTag == null || nodeByTag == null ? null : nodeByTag.get(nodeTag);
            String connectionId = buildConnectionId(conn, accountNo, clientIp, serverIp, nodeTag);
            String dedupeKey = accountNo + "\0" + connectionId + "\0" + serverIp;
            if (!seen.add(dedupeKey)) {
                continue;
            }
            LocalDateTime sessionStartTime = resolveConnectionStartTime(conn, now);
            try {
                accountOnlineIpRepository.upsertOnlineStatus(accountNo, clientIp, connectionId, serverIp,
                        node == null ? null : node.getId(), nodeTag, now, sessionStartTime, now, now, offlineThreshold);
                count++;
            } catch (Exception e) {
                log.error("refreshFromConnections upsert 失败: accountNo={}, clientIp={}, connectionId={}, serverIp={}",
                        accountNo, clientIp, connectionId, serverIp, e);
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

    private String buildConnectionId(SingBoxConnectionSnapshot conn,
                                     String accountNo,
                                     String clientIp,
                                     String serverIp,
                                     String nodeTag) {
        String rawId = normalizeBlank(conn.getId());
        if (rawId != null) {
            return rawId;
        }

        String destinationIp = normalizeBlank(conn.getMetadata().getDestinationIP());
        Integer destinationPort = conn.getMetadata().getDestinationPort();
        return String.join("|",
                "fallback",
                accountNo,
                clientIp,
                Objects.toString(serverIp, ""),
                Objects.toString(nodeTag, ""),
                Objects.toString(destinationIp, ""),
                Objects.toString(destinationPort, ""));
    }

    private LocalDateTime resolveConnectionStartTime(SingBoxConnectionSnapshot conn, LocalDateTime fallback) {
        String start = normalizeBlank(conn.getStart());
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

    private String legacyConnectionId(String clientIp, String nodeIp) {
        return String.join("|", "legacy", Objects.toString(clientIp, ""), Objects.toString(nodeIp, ""));
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
