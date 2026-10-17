package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.SystemLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class SystemLogCleanupTask {

    @Inject
    SystemLogService systemLogService;

    public void cleanupExpiredLogs() {
        log.info("开始执行定时任务：清理系统日志");
        try {
            log.info("系统日志清理完成，删除 {} 条", systemLogService.cleanupExpiredLogs());
        } catch (Exception e) {
            log.error("执行系统日志清理任务时发生错误", e);
        }
    }
}
