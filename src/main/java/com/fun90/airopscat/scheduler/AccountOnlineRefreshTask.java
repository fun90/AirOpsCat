package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.singbox.SingBoxOnlineConnectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class AccountOnlineRefreshTask {

    @Inject
    SingBoxOnlineConnectionService singBoxOnlineConnectionService;

    public void refreshOnlineAccounts() {
        log.debug("开始执行在线账号刷新任务");
        try {
            singBoxOnlineConnectionService.refreshAllServers();
        } catch (Exception e) {
            log.error("在线账号刷新任务执行失败", e);
        }
    }
}
