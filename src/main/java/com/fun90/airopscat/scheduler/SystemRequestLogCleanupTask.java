package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.SystemRequestLogProperties;
import com.fun90.airopscat.service.SystemRequestLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class SystemRequestLogCleanupTask {

    @Inject
    SystemRequestLogService systemRequestLogService;

    @Inject
    SystemRequestLogProperties systemRequestLogProperties;

    public void cleanupExpiredLogs() {
        log.info("开始执行定时任务：清理系统请求日志");
        try {
            long deletedCount = systemRequestLogService.cleanupExpiredLogs();
            log.info("系统请求日志清理完成，删除 {} 条 {} 天前的数据", deletedCount,
                    systemRequestLogProperties.getRetentionDays());
        } catch (Exception e) {
            log.error("执行系统请求日志清理任务时发生错误", e);
        }
    }
}
