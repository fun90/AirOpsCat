package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.service.ratelimit.RateLimitService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@ApplicationScoped
public class AccountTrafficOverQuotaService {

    private static final String ALERT_TYPE = "account-traffic-over-quota";
    private static final String RESOURCE_TYPE = "account";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RECOVERED = "RECOVERED";
    private static final String SEVERITY_WARNING = "WARNING";
    private static final String NOTIFY_INTERVAL_KEY = "airopscat.account.connection-limit.alert.min-interval-minutes";

    @Inject
    AlertStateRepository alertStateRepository;

    @Inject
    BarkService barkService;

    @Inject
    SystemConfigService systemConfigService;

    @Inject
    AccountTrafficLimitService accountTrafficLimitService;

    @Inject
    RateLimitService rateLimitService;

    @Transactional
    public void handle(Account account, AccountTrafficStats stats) {
        if (account == null || stats == null || account.getId() == null) {
            return;
        }

        long usedBytes = safe(stats.getUploadBytes()) + safe(stats.getDownloadBytes());
        Long effectiveBandwidth = accountTrafficLimitService.resolveEffectiveBandwidth(account, stats);
        if (accountTrafficLimitService.isOverQuota(usedBytes, effectiveBandwidth)) {
            rateLimitService.triggerAsyncSync();
            triggerAlert(account, stats, usedBytes, effectiveBandwidth);
            return;
        }

        recoverAlert(account, stats, usedBytes, effectiveBandwidth);
    }

    private void triggerAlert(Account account, AccountTrafficStats stats, long usedBytes, Long effectiveBandwidth) {
        LocalDateTime now = LocalDateTime.now();
        String periodFingerprint = periodFingerprint(account, stats);
        AlertState state = findCurrentPeriodState(account, stats, periodFingerprint)
                .orElseGet(() -> newAlertState(account, periodFingerprint, now));

        if (!STATUS_ACTIVE.equals(state.getStatus())) {
            if (STATUS_RECOVERED.equals(state.getStatus())) {
                activate(state, now);
            }
        }

        state.setLastTriggeredTime(now);
        state.setTriggerCount((state.getTriggerCount() == null ? 0 : state.getTriggerCount()) + 1);
        state.setLastValue(toGb(usedBytes));
        state.setThresholdValue(effectiveBandwidth == null ? null : effectiveBandwidth.doubleValue());
        state.setSummary(buildSummary(account, usedBytes, effectiveBandwidth));
        persistIfNew(state);

        if (!shouldNotify(state, now)) {
            return;
        }

        boolean sent = barkService.sendWarningNotification("AirOpsCat 账户流量超额", state.getSummary());
        if (sent) {
            state.setLastNotifiedTime(now);
        } else {
            log.warn("账户流量超额告警发送失败: accountNo={}", account.getAccountNo());
        }
    }

    private void recoverAlert(Account account, AccountTrafficStats stats, long usedBytes, Long effectiveBandwidth) {
        LocalDateTime now = LocalDateTime.now();
        findCurrentPeriodState(account, stats, periodFingerprint(account, stats))
                .filter(state -> STATUS_ACTIVE.equals(state.getStatus()) || "ACKNOWLEDGED".equals(state.getStatus()))
                .ifPresent(state -> {
                    state.setStatus(STATUS_RECOVERED);
                    state.setRecoveredTime(now);
                    state.setLastTriggeredTime(now);
                    state.setLastValue(toGb(usedBytes));
                    state.setThresholdValue(effectiveBandwidth == null ? null : effectiveBandwidth.doubleValue());
                    state.setSummary(buildRecoverySummary(account, usedBytes, effectiveBandwidth));
                    rateLimitService.triggerAsyncSync();
                });
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

    private void activate(AlertState state, LocalDateTime now) {
        state.setStatus(STATUS_ACTIVE);
        state.setFirstTriggeredTime(now);
        state.setRecoveredTime(null);
        state.setAcknowledgedTime(null);
        state.setAcknowledgedBy(null);
    }

    private Optional<AlertState> findCurrentPeriodState(Account account, AccountTrafficStats stats, String periodFingerprint) {
        Optional<AlertState> currentState = alertStateRepository
                .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, account.getId(), periodFingerprint);
        if (currentState.isPresent()) {
            return currentState;
        }

        return alertStateRepository
                .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, account.getId(), legacyFingerprint(account))
                .filter(state -> belongsToCurrentPeriod(state, stats))
                .map(state -> {
                    state.setFingerprint(periodFingerprint);
                    return state;
                });
    }

    private boolean shouldNotify(AlertState state, LocalDateTime now) {
        if ("ACKNOWLEDGED".equals(state.getStatus())) {
            return false;
        }
        int intervalMinutes = Math.max(0, systemConfigService.getIntValue(NOTIFY_INTERVAL_KEY, 60));
        return state.getLastNotifiedTime() == null || !state.getLastNotifiedTime().plusMinutes(intervalMinutes).isAfter(now);
    }

    private String buildSummary(Account account, long usedBytes, Long effectiveBandwidth) {
        return "账户: " + displayName(account)
                + "\n当前用量: " + formatGb(usedBytes)
                + "\n有效配额: " + (effectiveBandwidth == null ? "不限量" : effectiveBandwidth + " GB")
                + "\n超配降速已通过 sing-box 原生限速生效";
    }

    private String buildRecoverySummary(Account account, long usedBytes, Long effectiveBandwidth) {
        return "账户: " + displayName(account)
                + "\n当前用量: " + formatGb(usedBytes)
                + "\n有效配额: " + (effectiveBandwidth == null ? "不限量" : effectiveBandwidth + " GB")
                + "\n流量超额状态已恢复";
    }

    private String displayName(Account account) {
        return account.getAccountNo()
                + (account.getRemark() == null || account.getRemark().isBlank() ? "" : "（" + account.getRemark() + "）");
    }

    private String legacyFingerprint(Account account) {
        return account.getAccountNo() == null ? String.valueOf(account.getId()) : account.getAccountNo();
    }

    private String periodFingerprint(Account account, AccountTrafficStats stats) {
        String base = legacyFingerprint(account);
        if (stats == null || stats.getPeriodStart() == null) {
            return base;
        }
        return base + ":" + stats.getPeriodStart();
    }

    private boolean belongsToCurrentPeriod(AlertState state, AccountTrafficStats stats) {
        if (stats == null || stats.getPeriodStart() == null || stats.getPeriodEnd() == null) {
            return true;
        }
        LocalDateTime lastTriggeredTime = state.getLastTriggeredTime();
        return lastTriggeredTime == null
                || (!lastTriggeredTime.isBefore(stats.getPeriodStart()) && lastTriggeredTime.isBefore(stats.getPeriodEnd()));
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    private double toGb(long bytes) {
        return bytes / 1024.0 / 1024.0 / 1024.0;
    }

    private String formatGb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.2f GB", toGb(bytes));
    }

    private void persistIfNew(AlertState state) {
        if (state.getId() == null) {
            alertStateRepository.persist(state);
        }
    }

}
