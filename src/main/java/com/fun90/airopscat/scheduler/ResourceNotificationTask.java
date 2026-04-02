package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.expiration.MonitorNotificationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class ResourceNotificationTask {

    @Inject
    MonitorNotificationService monitorNotificationService;

    @Inject
    SystemConfigService systemConfigService;

    public void notifyExpiringResourcesToday() {
        monitorNotificationService.notify("account");
        monitorNotificationService.notify("server");
        monitorNotificationService.notify("domain");
    }

    public void notifyServerTrafficThreshold() {
        monitorNotificationService.notify("server-traffic");
    }

    public void notifyServerMonitorLoad() {
        if (!systemConfigService.getBooleanValue("airopscat.server.monitor.enabled", true)) {
            log.debug("服务器监控已禁用，跳过本次监控提醒");
            return;
        }
        monitorNotificationService.notify("server-monitor-load");
    }
}
