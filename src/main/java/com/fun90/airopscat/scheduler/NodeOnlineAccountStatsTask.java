package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.NodeOnlineAccountStatsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class NodeOnlineAccountStatsTask {

    @Inject
    NodeOnlineAccountStatsService nodeOnlineAccountStatsService;

    public void sampleDailyStats() {
        int sampledNodeCount = nodeOnlineAccountStatsService.sampleToday();
        log.info("节点每日在线账户统计采样完成，采样节点数: {}", sampledNodeCount);
    }
}
