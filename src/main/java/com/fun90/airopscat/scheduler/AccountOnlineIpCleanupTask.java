package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.AccountOnlineIpService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class AccountOnlineIpCleanupTask {

    @Inject
    AccountOnlineIpService accountOnlineIpService;

    public void cleanupOldRecords() {
        log.info("开始执行定时任务：清理历史在线连接记录");
        try {
            accountOnlineIpService.cleanupOldRecords();
        } catch (Exception e) {
            log.error("清理历史在线连接记录任务执行失败", e);
        }
    }
}
