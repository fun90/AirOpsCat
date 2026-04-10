package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.ServerMonitorStatsService;
import com.fun90.airopscat.service.SystemConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class ServerMonitorStatsCleanupTask {

    @Inject
    ServerMonitorStatsService serverMonitorStatsService;

    @Inject
    SystemConfigService systemConfigService;

    public void cleanupExpiredServerMonitorStats() {
        log.info("开始执行定时任务：清理过期服务器监控数据");

        try {
            long deletedCount = serverMonitorStatsService.cleanupExpiredStats();
            log.info("服务器监控数据清理完成，删除 {} 条 {} 天前的数据", deletedCount,
                    Math.max(systemConfigService.getIntValue("airopscat.server.monitor.retention-days", 30), 1));
        } catch (Exception e) {
            log.error("执行服务器监控数据清理任务时发生错误", e);
        }
    }
}
