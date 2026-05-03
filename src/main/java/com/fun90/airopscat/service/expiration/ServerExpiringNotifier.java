package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.AlertStateRepository;
import com.fun90.airopscat.repository.ServerRepository;
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
public class ServerExpiringNotifier implements MonitorNotifier {

    private static final String ALERT_TYPE = "server-expiring";
    private static final String RESOURCE_TYPE = "server";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RECOVERED = "RECOVERED";
    private static final String SEVERITY_INFO = "INFO";
    private static final int DEFAULT_NOTIFY_INTERVAL_HOURS = 23;

    @Inject
    ServerRepository serverRepository;

    @Inject
    AlertStateRepository alertStateRepository;

    @Inject
    BarkService barkService;

    @Inject
    SystemConfigService systemConfigService;

    @Override
    public String getType() {
        return "server";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 服务器到期提醒";
    }

    @Override
    @Transactional
    public List<String> findItems(LocalDate today) {
        List<Server> servers = serverRepository.findExpiringOnDate(today);

        LocalDateTime now = LocalDateTime.now();
        Set<Long> expiringIds = servers.stream()
                .map(Server::getId).filter(id -> id != null).collect(Collectors.toSet());

        List<String> itemsToNotify = new ArrayList<>();
        for (Server server : servers) {
            if (server.getId() == null) continue;
            String fp = fingerprint(server);
            AlertState state = alertStateRepository
                    .findByIdentity(ALERT_TYPE, RESOURCE_TYPE, server.getId(), fp)
                    .orElseGet(() -> newAlertState(server, fp, now));

            if (!STATUS_ACTIVE.equals(state.getStatus())) {
                state.setStatus(STATUS_ACTIVE);
                state.setFirstTriggeredTime(now);
                state.setRecoveredTime(null);
            }
            state.setLastTriggeredTime(now);
            state.setTriggerCount((state.getTriggerCount() == null ? 0 : state.getTriggerCount()) + 1);
            state.setSummary("服务器到期: " + displayName(server));
            persistIfNew(state);

            if (shouldNotify(state, now)) {
                itemsToNotify.add(displayName(server));
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
        return "今日到期服务器数: " + items.size() + "\n" + String.join(", ", items);
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
                "airopscat.server.expiring.alert.min-interval-hours", DEFAULT_NOTIFY_INTERVAL_HOURS));
        return state.getLastNotifiedTime() == null
                || !state.getLastNotifiedTime().plusHours(hours).isAfter(now);
    }

    private AlertState newAlertState(Server server, String fingerprint, LocalDateTime now) {
        AlertState state = new AlertState();
        state.setAlertType(ALERT_TYPE);
        state.setResourceType(RESOURCE_TYPE);
        state.setResourceId(server.getId());
        state.setResourceKey(fingerprint);
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

    private String fingerprint(Server server) {
        return server.getIp() != null ? server.getIp() : String.valueOf(server.getId());
    }

    private String displayName(Server server) {
        String name = server.getName();
        String ip = server.getIp();
        return (name != null && !name.isBlank()) ? name + " (" + ip + ")" : ip;
    }
}
