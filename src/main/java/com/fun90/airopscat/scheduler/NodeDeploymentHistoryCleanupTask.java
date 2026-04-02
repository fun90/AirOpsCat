package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.deployment.NodeDeploymentVersionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Slf4j
@ApplicationScoped
public class NodeDeploymentHistoryCleanupTask {

    @Inject
    NodeDeploymentVersionService nodeDeploymentVersionService;

    @ConfigProperty(name = "airopscat.node.deployment.history.retention-days", defaultValue = "90")
    int retentionDays;

    @ConfigProperty(name = "airopscat.node.deployment.history.keep-latest-per-node", defaultValue = "20")
    int keepLatestPerNode;

    public void cleanupExpiredHistory() {
        log.info("开始执行定时任务：清理节点部署历史");
        try {
            long deletedCount = nodeDeploymentVersionService.cleanupExpiredHistory();
            log.info("节点部署历史清理完成，删除 {} 条，保留 {} 天且每节点保留最近 {} 个历史版本",
                    deletedCount, Math.max(retentionDays, 1), Math.max(keepLatestPerNode, 0));
        } catch (Exception e) {
            log.error("执行节点部署历史清理任务时发生错误", e);
        }
    }
}
