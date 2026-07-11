package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.guard.AccountGuardAggregator;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 低频清理 guard 聚合表中的僵尸节点格（超 TTL 未上报）。
 *
 * <p>注意：聚合判定本身在求和时已跳过过期格（见 {@code AccountGuardAggregator.evaluateAccount}），
 * 因此本任务不影响判定正确性，仅用于回收长期不上报节点残留的内存，防止无界增长。
 * 频率远低于 guard-sync 热路径，不参与配额计算。
 */
@Slf4j
@ApplicationScoped
public class AccountGuardEvictTask {

    @Inject
    AccountGuardAggregator accountGuardAggregator;

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void evictStaleNodes() {
        try {
            accountGuardAggregator.evictStaleNodes();
        } catch (Exception e) {
            log.warn("guard 聚合表清理失败: {}", e.getMessage());
        }
    }
}
