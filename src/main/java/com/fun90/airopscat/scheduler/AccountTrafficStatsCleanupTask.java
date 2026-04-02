package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.AccountTrafficStatsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Slf4j
@ApplicationScoped
public class AccountTrafficStatsCleanupTask {

    @Inject
    AccountTrafficStatsService accountTrafficStatsService;

    @ConfigProperty(name = "airopscat.account.traffic.retention-days", defaultValue = "180")
    int retentionDays;

    public void cleanupExpiredStats() {
        log.info("开始执行定时任务：清理账户流量明细");
        try {
            long deletedCount = accountTrafficStatsService.cleanupExpiredStats();
            log.info("账户流量明细清理完成，删除 {} 条 {} 天前的数据", deletedCount, Math.max(retentionDays, 1));
        } catch (Exception e) {
            log.error("执行账户流量明细清理任务时发生错误", e);
        }
    }
}
