package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.service.DomainDnsPushService;
import com.fun90.airopscat.service.DomainDnsRecordService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 每分钟按时段计划刷新 DNS 记录内容，并推送到 DNS 服务商。
 */
@Slf4j
@ApplicationScoped
public class DomainDnsScheduleTask {

    @Inject
    DomainDnsRecordService domainDnsRecordService;

    @Inject
    DomainDnsPushService domainDnsPushService;

    @Scheduled(cron = "0 * * * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void applySchedules() {
        try {
            for (Long domainId : domainDnsRecordService.applySchedules()) {
                try {
                    domainDnsPushService.push(domainId);
                } catch (Exception e) {
                    log.warn("DNS 时段计划推送失败, domainId={}: {}", domainId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("DNS 时段计划执行失败: {}", e.getMessage());
        }
    }
}
