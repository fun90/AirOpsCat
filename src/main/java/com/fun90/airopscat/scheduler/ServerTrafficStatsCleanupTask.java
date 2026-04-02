package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.ServerTrafficStatsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Slf4j
@ApplicationScoped
public class ServerTrafficStatsCleanupTask {

    @Inject
    ServerTrafficStatsService serverTrafficStatsService;

    @ConfigProperty(name = "airopscat.server.traffic.retention-days", defaultValue = "180")
    int retentionDays;

    public void cleanupExpiredStats() {
        log.info("开始执行定时任务：清理服务器流量明细");
        try {
            long deletedCount = serverTrafficStatsService.cleanupExpiredStats();
            log.info("服务器流量明细清理完成，删除 {} 条 {} 天前的数据", deletedCount, Math.max(retentionDays, 1));
        } catch (Exception e) {
            log.error("执行服务器流量明细清理任务时发生错误", e);
        }
    }
}
