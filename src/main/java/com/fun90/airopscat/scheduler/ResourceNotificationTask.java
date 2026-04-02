package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.expiration.MonitorNotificationService;
import io.quarkus.scheduler.Scheduled;
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

    @Scheduled(cron = "{airopscat.expiration.notify.cron:0 0 10 * * ?}", timeZone = "Asia/Shanghai")
    public void notifyExpiringResourcesToday() {
        monitorNotificationService.notify("account");
        monitorNotificationService.notify("server");
        monitorNotificationService.notify("domain");
    }

    @Scheduled(cron = "{airopscat.server.traffic.notify.cron:0 10 10 * * ?}", timeZone = "Asia/Shanghai")
    public void notifyServerTrafficThreshold() {
        monitorNotificationService.notify("server-traffic");
    }

    @Scheduled(
            cron = "{airopscat.server.monitor.alert.cron:0 */5 * * * ?}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    public void notifyServerMonitorLoad() {
        if (!systemConfigService.getBooleanValue("airopscat.server.monitor.enabled", true)) {
            log.debug("服务器监控已禁用，跳过本次监控提醒");
            return;
        }
        monitorNotificationService.notify("server-monitor-load");
    }
}
