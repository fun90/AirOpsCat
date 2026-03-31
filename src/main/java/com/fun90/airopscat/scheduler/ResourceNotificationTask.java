package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.expiration.MonitorNotificationService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Slf4j
@ApplicationScoped
public class ResourceNotificationTask {

    @Inject
    MonitorNotificationService monitorNotificationService;

    @ConfigProperty(name = "airopscat.server.monitor.enabled", defaultValue = "true")
    boolean serverMonitorEnabled;

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
        if (!serverMonitorEnabled) {
            log.debug("服务器监控采集已禁用，跳过本次监控提醒");
            return;
        }
        monitorNotificationService.notify("server-monitor-load");
    }
}
