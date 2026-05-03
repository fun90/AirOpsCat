package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.repository.DomainRepository;
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
public class DomainExpiringNotifier implements MonitorNotifier {

    private static final String ALERT_TYPE = "domain-expiring";
    private static final String RESOURCE_TYPE = "domain";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RECOVERED = "RECOVERED";
    private static final String SEVERITY_INFO = "INFO";
    private static final int DEFAULT_NOTIFY_INTERVAL_HOURS = 23;

    @Inject
    DomainRepository domainRepository;

    @Inject
    AlertStateRepository alertStateRepository;

    @Inject
    BarkService barkService;

    @Inject
    SystemConfigService systemConfigService;

    @Override
    public String getType() {
        return "domain";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 域名到期提醒";
    }

    @Override
    @Transactional
    public List<String> findItems(LocalDate today) {
        List<Domain> domains = domainRepository.findExpiringOnDate(today);

        LocalDateTime now = LocalDateTime.now();
        Set<Long> expiringIds = domains.stream()
                .map(Domain::getId).filter(id -> id != null).collect(Collectors.toSet());

        List<String> itemsToNotify = new ArrayList<>();
        for (Domain domain : domains) {
            if (domain.getId() == null || domain.getDomain() == null) continue;
            String fp = domain.getDomain();
            AlertState state = alertStateRepository
                    .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, domain.getId(), fp)
                    .orElseGet(() -> newAlertState(domain, fp, now));

            if (!STATUS_ACTIVE.equals(state.getStatus())) {
                state.setStatus(STATUS_ACTIVE);
                state.setFirstTriggeredTime(now);
                state.setRecoveredTime(null);
            }
            state.setLastTriggeredTime(now);
            state.setTriggerCount((state.getTriggerCount() == null ? 0 : state.getTriggerCount()) + 1);
            state.setSummary("域名到期: " + domain.getDomain());
            persistIfNew(state);

            if (shouldNotify(state, now)) {
                itemsToNotify.add(domain.getDomain());
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
        return "今日到期域名数: " + items.size() + "\n" + String.join(", ", items);
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
                "airopscat.domain.expiring.alert.min-interval-hours", DEFAULT_NOTIFY_INTERVAL_HOURS));
        return state.getLastNotifiedTime() == null
                || !state.getLastNotifiedTime().plusHours(hours).isAfter(now);
    }

    private AlertState newAlertState(Domain domain, String fingerprint, LocalDateTime now) {
        AlertState state = new AlertState();
        state.setAlertType(ALERT_TYPE);
        state.setResourceType(RESOURCE_TYPE);
        state.setResourceId(domain.getId());
        state.setResourceKey(domain.getDomain());
        state.setFingerprint(fingerprint);
        state.setStatus(STATUS_ACTIVE);
        state.setSeverity(SEVERITY_INFO);
        state.setFirstTriggeredTime(now);
        state.setTriggerCount(0);
        return state;
    }

    private void persistIfNew(AlertState state) {
        if (state.getId() == null) alertStateRepository.persist(state);
    }
}
