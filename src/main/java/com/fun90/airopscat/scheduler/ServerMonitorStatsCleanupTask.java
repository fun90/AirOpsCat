package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.ServerMonitorStatsService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Slf4j
@ApplicationScoped
public class ServerMonitorStatsCleanupTask {

    @Inject
    ServerMonitorStatsService serverMonitorStatsService;

    @ConfigProperty(name = "airopscat.server.monitor.retention-days", defaultValue = "30")
    int monitorRetentionDays;

    @Scheduled(cron = "{airopscat.server.monitor.cleanup.cron:0 0 3 * * ?}", timeZone = "Asia/Shanghai")
    public void cleanupExpiredServerMonitorStats() {
        log.info("开始执行定时任务：清理过期服务器监控数据");

        try {
            long deletedCount = serverMonitorStatsService.cleanupExpiredStats();
            log.info("服务器监控数据清理完成，删除 {} 条 {} 天前的数据", deletedCount, Math.max(monitorRetentionDays, 1));
        } catch (Exception e) {
            log.error("执行服务器监控数据清理任务时发生错误", e);
        }
    }
}
