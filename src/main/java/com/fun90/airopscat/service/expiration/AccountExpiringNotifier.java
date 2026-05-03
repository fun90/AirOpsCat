package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.SystemConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class AccountExpiringNotifier implements MonitorNotifier {

    private static final String ALERT_TYPE = "account-expiring";
    private static final String RESOURCE_TYPE = "account";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RECOVERED = "RECOVERED";
    private static final String SEVERITY_INFO = "INFO";
    private static final int DEFAULT_NOTIFY_INTERVAL_HOURS = 23;

    @Inject
    AccountRepository accountRepository;

    @Inject
    AlertStateRepository alertStateRepository;

    @Inject
    BarkService barkService;

    @Inject
    SystemConfigService systemConfigService;

    @Override
    public String getType() {
        return "account";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 到期提醒";
    }

    @Override
    @Transactional
    public List<String> findItems(LocalDate today) {
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);
        List<Account> expiringAccounts = accountRepository.findExpiringOnDate(startOfDay, endOfDay);

        LocalDateTime now = LocalDateTime.now();
        Set<Long> expiringIds = expiringAccounts.stream()
                .map(Account::getId).filter(id -> id != null).collect(Collectors.toSet());

        List<String> itemsToNotify = new ArrayList<>();
        for (Account account : expiringAccounts) {
            if (account.getId() == null) continue;
            AlertState state = alertStateRepository
                    .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, account.getId(), fingerprint(account))
                    .orElseGet(() -> newAlertState(account, now));

            if (!STATUS_ACTIVE.equals(state.getStatus())) {
                state.setStatus(STATUS_ACTIVE);
                state.setFirstTriggeredTime(now);
                state.setRecoveredTime(null);
            }
            state.setLastTriggeredTime(now);
            state.setTriggerCount((state.getTriggerCount() == null ? 0 : state.getTriggerCount()) + 1);
            state.setSummary("账户到期: " + displayName(account));
            persistIfNew(state);

            if (shouldNotify(state, now)) {
                itemsToNotify.add(displayName(account));
                state.setLastNotifiedTime(now);
            }
        }

        if (!itemsToNotify.isEmpty()) {
            barkService.sendInfoNotification(getTitle(), buildBody(itemsToNotify));
        }

        recoverStaleAlerts(expiringIds, now);
        return List.of();
    }

    @Override
    public String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        return "今日到期账号数: " + items.size() + "\n" + String.join(", ", items);
    }

    private void recoverStaleAlerts(Set<Long> expiringIds, LocalDateTime now) {
        alertStateRepository.findActiveByAlertType(ALERT_TYPE).forEach(state -> {
            if (!expiringIds.contains(state.getResourceId())) {
                state.setStatus(STATUS_RECOVERED);
                state.setRecoveredTime(now);
                state.setLastTriggeredTime(now);
            }
        });
    }

    private boolean shouldNotify(AlertState state, LocalDateTime now) {
        int hours = Math.max(0, systemConfigService.getIntValue(
                "airopscat.account.expiring.alert.min-interval-hours", DEFAULT_NOTIFY_INTERVAL_HOURS));
        return state.getLastNotifiedTime() == null
                || !state.getLastNotifiedTime().plusHours(hours).isAfter(now);
    }

    private AlertState newAlertState(Account account, LocalDateTime now) {
        AlertState state = new AlertState();
        state.setAlertType(ALERT_TYPE);
        state.setResourceType(RESOURCE_TYPE);
        state.setResourceId(account.getId());
        state.setResourceKey(account.getAccountNo());
        state.setFingerprint(fingerprint(account));
        state.setStatus(STATUS_ACTIVE);
        state.setSeverity(SEVERITY_INFO);
        state.setFirstTriggeredTime(now);
        state.setTriggerCount(0);
        return state;
    }

    private void persistIfNew(AlertState state) {
        if (state.getId() == null) alertStateRepository.persist(state);
    }

    private String fingerprint(Account account) {
        return account.getAccountNo() != null ? account.getAccountNo() : String.valueOf(account.getId());
    }

    private String displayName(Account account) {
        String remark = account.getRemark();
        return (remark != null && !remark.isBlank()) ? remark : account.getAccountNo();
    }
}
