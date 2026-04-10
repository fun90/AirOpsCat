package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.ServerTrafficStatsService;
import com.fun90.airopscat.service.SystemConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class ServerTrafficStatsCleanupTask {

    @Inject
    ServerTrafficStatsService serverTrafficStatsService;

    @Inject
    SystemConfigService systemConfigService;

    public void cleanupExpiredStats() {
        log.info("开始执行定时任务：清理服务器流量明细");
        try {
            long deletedCount = serverTrafficStatsService.cleanupExpiredStats();
            log.info("服务器流量明细清理完成，删除 {} 条 {} 天前的数据", deletedCount,
                    Math.max(systemConfigService.getIntValue("airopscat.server.traffic.retention-days", 180), 1));
        } catch (Exception e) {
            log.error("执行服务器流量明细清理任务时发生错误", e);
        }
    }
}
