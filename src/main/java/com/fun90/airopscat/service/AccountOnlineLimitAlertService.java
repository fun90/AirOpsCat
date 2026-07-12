package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AccountOnlineIpDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.service.guard.AccountGuardStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class AccountOnlineLimitAlertService {
    private static final String ALERT_TYPE = "account-connection-limit";
    private static final String RESOURCE_TYPE = "account";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    private static final String STATUS_RECOVERED = "RECOVERED";
    private static final String SEVERITY_WARNING = "WARNING";
    private static final int DEFAULT_CONSECUTIVE_TIMES = 2;

    private final Map<String, Integer> consecutiveExceedCounts = new ConcurrentHashMap<>();

    private final AccountRepository accountRepository;
    private final AccountOnlineIpService accountOnlineIpService;
    private final AlertStateRepository alertStateRepository;
    private final BarkService barkService;
    private final SystemConfigService systemConfigService;

    @Inject
    public AccountOnlineLimitAlertService(AccountRepository accountRepository,
                                          AccountOnlineIpService accountOnlineIpService,
                                          AlertStateRepository alertStateRepository,
                                          BarkService barkService,
                                          SystemConfigService systemConfigService) {
        this.accountRepository = accountRepository;
        this.accountOnlineIpService = accountOnlineIpService;
        this.alertStateRepository = alertStateRepository;
        this.barkService = barkService;
        this.systemConfigService = systemConfigService;
    }

    @Transactional
    public void checkAndNotify() {
        if (!isEnabled()) {
            log.debug("账户连接数超限告警已关闭");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<Account> accounts = accountRepository.findActiveConnectionLimitedAccounts(now);
        Map<String, Account> accountByNo = accounts.stream()
                .filter(account -> account.getAccountNo() != null)
                .collect(Collectors.toMap(Account::getAccountNo, Function.identity(), (left, right) -> left));

        Map<String, List<AccountOnlineIpDto>> recordsByAccount = accountOnlineIpService
                .getOnlineRecordsByAccountNos(accountByNo.keySet().stream().toList())
                .stream()
                .collect(Collectors.groupingBy(AccountOnlineIpDto::getAccountNo));

        for (Account account : accounts) {
            try {
                checkAccountFromOnlineRecords(account, recordsByAccount.getOrDefault(account.getAccountNo(), List.of()), now);
            } catch (Exception e) {
                log.warn("账户连接数超限检查失败: accountNo={}, error={}", account.getAccountNo(), e.getMessage(), e);
            }
        }

        consecutiveExceedCounts.keySet().removeIf(accountNo -> !accountByNo.containsKey(accountNo));
        recoverInactiveAlerts(accountByNo, recordsByAccount, now);
    }

    @Transactional
    public void checkAndNotifyFromGuard(Map<String, AccountGuardStats> statsByAccountNo) {
        if (!isEnabled()) {
            log.debug("账户连接数超限告警已关闭，跳过 guard 实时告警");
            return;
        }
        if (statsByAccountNo == null || statsByAccountNo.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<String> accountNos = statsByAccountNo.keySet().stream()
                .filter(Objects::nonNull)
                .filter(accountNo -> !accountNo.isBlank())
                .toList();
        if (accountNos.isEmpty()) {
            return;
        }

        Map<String, Account> accountByNo = loadAccounts(accountNos);
        for (Map.Entry<String, AccountGuardStats> entry : statsByAccountNo.entrySet()) {
            String accountNo = entry.getKey();
            Account account = accountByNo.get(accountNo);
            AccountGuardStats stats = entry.getValue();
            try {
                if (account == null || !isActiveConnectionLimited(account, now)) {
                    recoverActiveAlertByAccountNo(accountNo, 0, 0, "账户已不再需要连接数超限告警", now);
                    consecutiveExceedCounts.remove(accountNo);
                    continue;
                }
                checkAccountFromGuard(account, stats, now);
            } catch (Exception e) {
                log.warn("guard 实时连接数告警检查失败: accountNo={}, error={}", accountNo, e.getMessage(), e);
            }
        }
    }

    private Map<String, Account> loadAccounts(List<String> accountNos) {
        Map<String, Account> accountByNo = new HashMap<>();
        for (Account account : accountRepository.findByAccountNos(new HashSet<>(accountNos))) {
            if (account.getAccountNo() != null) {
                accountByNo.put(account.getAccountNo(), account);
            }
        }
        return accountByNo;
    }

    private void checkAccountFromOnlineRecords(Account account, List<AccountOnlineIpDto> records, LocalDateTime now) {
        int limit = account.getMaxConnections() == null ? 0 : account.getMaxConnections();
        if (limit <= 0) {
            consecutiveExceedCounts.remove(account.getAccountNo());
            return;
        }

        int connectionCount = records.size();
        if (connectionCount > limit) {
            triggerOrCount(account, limit, connectionCount, buildSummary(account, records, limit, connectionCount), now);
            return;
        }

        consecutiveExceedCounts.remove(account.getAccountNo());
        recoverAlert(account, connectionCount, limit, "当前连接数 " + connectionCount + "，限制 " + limit, now);
    }

    private void checkAccountFromGuard(Account account, AccountGuardStats stats, LocalDateTime now) {
        int limit = account.getMaxConnections() == null ? 0 : account.getMaxConnections();
        if (limit <= 0) {
            consecutiveExceedCounts.remove(account.getAccountNo());
            return;
        }

        int connectionCount = stats == null ? 0 : stats.getTotalConnections();
        if (connectionCount > limit) {
            triggerOrCount(account, limit, connectionCount, buildGuardSummary(account, stats, limit), now);
            return;
        }

        consecutiveExceedCounts.remove(account.getAccountNo());
        recoverAlert(account, connectionCount, limit, "当前连接数 " + connectionCount + "，限制 " + limit, now);
    }

    private void triggerOrCount(Account account, int limit, int connectionCount, String summary, LocalDateTime now) {
        int consecutiveTimes = consecutiveExceedCounts.merge(account.getAccountNo(), 1, Integer::sum);
        int requiredTimes = getConsecutiveTimesThreshold();
        if (consecutiveTimes >= requiredTimes) {
            triggerAlert(account, limit, connectionCount, summary, now);
        } else {
            log.debug("账户连接数超限，未达到连续 {} 次阈值，当前连续次数: {}, accountNo={}",
                    requiredTimes, consecutiveTimes, account.getAccountNo());
        }
    }

    private int getConsecutiveTimesThreshold() {
        return Math.max(1, systemConfigService.getIntValue(
                "airopscat.account.connection-limit.alert.consecutive-times", DEFAULT_CONSECUTIVE_TIMES));
    }

    private void triggerAlert(Account account,
                              int limit,
                              int connectionCount,
                              String summary,
                              LocalDateTime now) {
        String fingerprint = fingerprint(account);
        AlertState state = alertStateRepository
                .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, account.getId(), fingerprint)
                .orElseGet(() -> newAlertState(account, fingerprint, now));

        if (!STATUS_ACTIVE.equals(state.getStatus())) {
            state.setStatus(STATUS_ACTIVE);
            state.setFirstTriggeredTime(now);
            state.setRecoveredTime(null);
        }
        state.setLastTriggeredTime(now);
        state.setTriggerCount((state.getTriggerCount() == null ? 0 : state.getTriggerCount()) + 1);
        state.setLastValue((double) connectionCount);
        state.setThresholdValue((double) limit);
        state.setSummary(summary);
        persistIfNew(state);

        if (!shouldNotify(state, now)) {
            return;
        }

        boolean sent = barkService.sendWarningNotification("AirOpsCat 账户连接数超限", state.getSummary());
        if (sent) {
            state.setLastNotifiedTime(now);
        } else {
            log.warn("账户连接数超限告警发送失败: accountNo={}", account.getAccountNo());
        }
    }

    private void recoverInactiveAlerts(Map<String, Account> accountByNo,
                                       Map<String, List<AccountOnlineIpDto>> recordsByAccount,
                                       LocalDateTime now) {
        for (AlertState state : alertStateRepository.findActiveByAlertType(ALERT_TYPE)) {
            Account account = accountByNo.get(state.getResourceKey());
            if (account == null || account.getMaxConnections() == null || account.getMaxConnections() <= 0) {
                consecutiveExceedCounts.remove(state.getResourceKey());
                recoverState(state, 0, 0, "账户已不再需要连接数超限告警", now);
                continue;
            }
            int connectionCount = recordsByAccount.getOrDefault(account.getAccountNo(), List.of()).size();
            if (connectionCount <= account.getMaxConnections()) {
                consecutiveExceedCounts.remove(account.getAccountNo());
                recoverState(state, connectionCount, account.getMaxConnections(),
                        "当前连接数 " + connectionCount + "，限制 " + account.getMaxConnections(), now);
            }
        }
    }

    private void recoverActiveAlertByAccountNo(String accountNo,
                                               int connectionCount,
                                               int limit,
                                               String summary,
                                               LocalDateTime now) {
        for (AlertState state : alertStateRepository.findActiveByAlertType(ALERT_TYPE)) {
            if (Objects.equals(state.getResourceKey(), accountNo)) {
                recoverState(state, connectionCount, limit, summary, now);
            }
        }
    }

    private void recoverAlert(Account account, int connectionCount, int limit, String summary, LocalDateTime now) {
        alertStateRepository.findByIdentity(ALERT_TYPE, RESOURCE_TYPE, account.getId(), fingerprint(account))
                .filter(state -> STATUS_ACTIVE.equals(state.getStatus()))
                .ifPresent(state -> recoverState(state, connectionCount, limit, summary, now));
    }

    private void recoverState(AlertState state, int connectionCount, int limit, String summary, LocalDateTime now) {
        state.setStatus(STATUS_RECOVERED);
        state.setRecoveredTime(now);
        state.setLastTriggeredTime(now);
        state.setLastValue((double) connectionCount);
        state.setThresholdValue((double) limit);
        state.setSummary(summary);

        if (systemConfigService.getBooleanValue("airopscat.account.connection-limit.alert.recovery-notify-enabled", false)
                && shouldNotify(state, now)) {
            boolean sent = barkService.sendInfoNotification("AirOpsCat 账户连接数恢复", summary);
            if (sent) {
                state.setLastNotifiedTime(now);
            }
        }
    }

    private boolean shouldNotify(AlertState state, LocalDateTime now) {
        if (STATUS_ACKNOWLEDGED.equals(state.getStatus())) {
            return false;
        }
        int intervalMinutes = Math.max(0, systemConfigService.getIntValue(
                "airopscat.account.connection-limit.alert.min-interval-minutes", 60));
        return state.getLastNotifiedTime() == null || !state.getLastNotifiedTime().plusMinutes(intervalMinutes).isAfter(now);
    }

    private AlertState newAlertState(Account account, String fingerprint, LocalDateTime now) {
        AlertState state = new AlertState();
        state.setAlertType(ALERT_TYPE);
        state.setResourceType(RESOURCE_TYPE);
        state.setResourceId(account.getId());
        state.setResourceKey(account.getAccountNo());
        state.setFingerprint(fingerprint);
        state.setStatus(STATUS_ACTIVE);
        state.setSeverity(SEVERITY_WARNING);
        state.setFirstTriggeredTime(now);
        state.setTriggerCount(0);
        return state;
    }

    private String buildSummary(Account account, List<AccountOnlineIpDto> records, int limit, int connectionCount) {
        long distinctIps = records.stream()
                .map(AccountOnlineIpDto::getClientIp)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return accountTitle(account)
                + "\n当前连接数: " + connectionCount
                + "\n连接数限制: " + limit
                + "\n去重客户端 IP 数: " + distinctIps;
    }

    private String buildGuardSummary(Account account, AccountGuardStats stats, int limit) {
        int connectionCount = stats == null ? 0 : stats.getTotalConnections();
        int totalIps = stats == null ? 0 : stats.getTotalIps();
        int activeNodeCount = stats == null ? 0 : stats.getActiveNodeCount();
        return accountTitle(account)
                + "\n当前连接数: " + connectionCount
                + "\n连接数限制: " + limit
                + "\n去重客户端 IP 数: " + totalIps
                + "\n活跃节点数: " + activeNodeCount
                + "\n数据来源: guard 实时上报";
    }

    private String accountTitle(Account account) {
        return "账户: " + account.getAccountNo()
                + (account.getRemark() == null || account.getRemark().isBlank() ? "" : "（" + account.getRemark() + "）");
    }

    private void persistIfNew(AlertState state) {
        if (state.getId() == null) {
            alertStateRepository.persist(state);
        }
    }

    private boolean isEnabled() {
        return systemConfigService.getBooleanValue("airopscat.account.connection-limit.alert.enabled", true);
    }

    private boolean isActiveConnectionLimited(Account account, LocalDateTime now) {
        return account != null
                && (account.getDisabled() == null || account.getDisabled() == 0)
                && account.getAccountNo() != null
                && account.getMaxConnections() != null
                && account.getMaxConnections() > 0
                && (account.getToDate() == null || account.getToDate().isAfter(now));
    }

    private String fingerprint(Account account) {
        return account.getAccountNo() == null ? String.valueOf(account.getId()) : account.getAccountNo();
    }
}
