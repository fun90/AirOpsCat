package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.service.ratelimit.RateLimitService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

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

    public void handle(Account account, AccountTrafficStats stats) {
        if (account == null || stats == null || account.getId() == null) {
            return;
        }

        long usedBytes = safe(stats.getUploadBytes()) + safe(stats.getDownloadBytes());
        Long effectiveBandwidth = accountTrafficLimitService.resolveEffectiveBandwidth(account, stats);
        if (accountTrafficLimitService.isOverQuota(usedBytes, effectiveBandwidth)) {
            rateLimitService.triggerAsyncSync();
            triggerAlert(account, usedBytes, effectiveBandwidth);
            return;
        }

        recoverAlert(account, usedBytes, effectiveBandwidth);
    }

    private void triggerAlert(Account account, long usedBytes, Long effectiveBandwidth) {
        LocalDateTime now = LocalDateTime.now();
        String fingerprint = fingerprint(account);
        AlertState state = alertStateRepository
                .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, account.getId(), fingerprint)
                .orElseGet(() -> newAlertState(account, fingerprint, now));

        if (!STATUS_ACTIVE.equals(state.getStatus())) {
            state.setStatus(STATUS_ACTIVE);
            state.setFirstTriggeredTime(now);
            state.setRecoveredTime(null);
        }

        AccountTrafficLimitService.EffectiveSpeedLimit limit =
                accountTrafficLimitService.resolveEffectiveSpeed(account, usedBytes, effectiveBandwidth);
        state.setLastTriggeredTime(now);
        state.setTriggerCount((state.getTriggerCount() == null ? 0 : state.getTriggerCount()) + 1);
        state.setLastValue(toGb(usedBytes));
        state.setThresholdValue(effectiveBandwidth == null ? null : effectiveBandwidth.doubleValue());
        state.setSummary(buildSummary(account, usedBytes, effectiveBandwidth, limit.speed()));
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

    private void recoverAlert(Account account, long usedBytes, Long effectiveBandwidth) {
        LocalDateTime now = LocalDateTime.now();
        alertStateRepository.findByIdentity(ALERT_TYPE, RESOURCE_TYPE, account.getId(), fingerprint(account))
                .filter(state -> STATUS_ACTIVE.equals(state.getStatus()))
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

    private boolean shouldNotify(AlertState state, LocalDateTime now) {
        int intervalMinutes = Math.max(0, systemConfigService.getIntValue(NOTIFY_INTERVAL_KEY, 60));
        return state.getLastNotifiedTime() == null || !state.getLastNotifiedTime().plusMinutes(intervalMinutes).isAfter(now);
    }

    private String buildSummary(Account account, long usedBytes, Long effectiveBandwidth, Integer effectiveSpeed) {
        return "账户: " + displayName(account)
                + "\n当前用量: " + formatGb(usedBytes)
                + "\n有效配额: " + (effectiveBandwidth == null ? "不限量" : effectiveBandwidth + " GB")
                + "\n当前限速: " + (effectiveSpeed == null || effectiveSpeed <= 0 ? "无限制" : effectiveSpeed + " KB/s");
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

    private String fingerprint(Account account) {
        return account.getAccountNo() == null ? String.valueOf(account.getId()) : account.getAccountNo();
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
