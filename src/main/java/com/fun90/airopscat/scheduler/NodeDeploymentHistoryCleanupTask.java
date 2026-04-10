package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.deployment.NodeDeploymentVersionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class NodeDeploymentHistoryCleanupTask {

    @Inject
    NodeDeploymentVersionService nodeDeploymentVersionService;

    @Inject
    SystemConfigService systemConfigService;

    public void cleanupExpiredHistory() {
        log.info("开始执行定时任务：清理节点部署历史");
        try {
            long deletedCount = nodeDeploymentVersionService.cleanupExpiredHistory();
            log.info("节点部署历史清理完成，删除 {} 条，保留 {} 天且每节点保留最近 {} 个历史版本",
                    deletedCount, Math.max(systemConfigService.getIntValue("airopscat.node.deployment.history.retention-days", 90), 1),
                    Math.max(systemConfigService.getIntValue("airopscat.node.deployment.history.keep-latest-per-node", 20), 0));
        } catch (Exception e) {
            log.error("执行节点部署历史清理任务时发生错误", e);
        }
    }
}
