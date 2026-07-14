package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.AccountOnlineLimitAlertService;
import com.fun90.airopscat.service.guard.AccountGuardAggregator;
import com.fun90.airopscat.service.guard.AccountGuardEvaluation;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

/**
 * 账户 Guard 中心采样任务。同一周期无论收到多少个服务器 agent 上报，
 * 黑名单与告警的连续超限次数都只累计一次。
 */
@Slf4j
@ApplicationScoped
public class AccountGuardEvaluationTask {

    @Inject
    AccountGuardAggregator accountGuardAggregator;

    @Inject
    AccountOnlineLimitAlertService accountOnlineLimitAlertService;

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void evaluateAccounts() {
        AccountGuardEvaluation evaluation;
        try {
            evaluation = accountGuardAggregator.evaluateTrackedAccounts();
        } catch (Exception e) {
            log.warn("Guard 中心采样评估失败: {}", e.getMessage(), e);
            return;
        }

        try {
            accountOnlineLimitAlertService.checkAndNotifyFromGuard(evaluation.getStatsByAccountNo());
        } catch (Exception e) {
            log.warn("Guard 中心采样告警检查失败: {}", e.getMessage(), e);
        }
    }
}
