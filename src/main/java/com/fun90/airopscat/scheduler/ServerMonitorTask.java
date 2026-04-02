package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.ServerMonitorStatsService;
import com.fun90.airopscat.service.SystemConfigService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@ApplicationScoped
public class ServerMonitorTask {

    private static final String SERVER_MONITOR_COLLECT_JOB_ID = "server-monitor-collect";

    @Inject
    ServerRepository serverRepository;

    @Inject
    ServerMonitorStatsService serverMonitorStatsService;

    @Inject
    @Named("blockingTaskExecutor")
    ExecutorService blockingTaskExecutor;

    @Inject
    Scheduler scheduler;

    @Inject
    SystemConfigService systemConfigService;

    void scheduleServerMonitorCollection(@Observes StartupEvent event) {
        long refreshMinutes = getRefreshMinutes();
        String cron = "0 */" + refreshMinutes + " * * * ?";
        scheduler.unscheduleJob(SERVER_MONITOR_COLLECT_JOB_ID);
        scheduler.newJob(SERVER_MONITOR_COLLECT_JOB_ID)
                .setCron(cron)
                .setTimeZone("Asia/Shanghai")
                .setConcurrentExecution(Scheduled.ConcurrentExecution.SKIP)
                .setTask(execution -> collectServerMonitorStats())
                .schedule();
        log.info("服务器监控采集调度已注册，refreshMinutes={}, cron={}", refreshMinutes, cron);
    }

    public void collectServerMonitorStats() {
        if (!systemConfigService.getBooleanValue("airopscat.server.monitor.enabled", true)) {
            log.debug("服务器监控采集已禁用，跳过本次任务");
            return;
        }

        log.info("开始执行定时任务：采集服务器监控指标");

        try {
            List<Server> servers = serverRepository.findMonitorableServers(LocalDateTime.now().toLocalDate());
            if (servers.isEmpty()) {
                log.info("没有找到服务器，跳过监控采集");
                return;
            }

            int skippedCount = 0;
            int successCount = 0;
            int failureCount = 0;

            List<CompletableFuture<MonitorCollectResult>> futures = servers.stream()
                    .map(server -> CompletableFuture.supplyAsync(
                            () -> collectServerMonitorSnapshot(server),
                            blockingTaskExecutor))
                    .toList();

            for (CompletableFuture<MonitorCollectResult> future : futures) {
                try {
                    MonitorCollectResult result = future.join();
                    if (result == MonitorCollectResult.SUCCESS) {
                        successCount++;
                    } else if (result == MonitorCollectResult.SKIPPED) {
                        skippedCount++;
                    } else {
                        failureCount++;
                    }
                } catch (Exception e) {
                    failureCount++;
                    log.error("等待服务器监控采集结果时发生错误: {}", e.getMessage(), e);
                }
            }

            log.info("服务器监控采集完成，成功: {}, 跳过: {}, 失败: {}", successCount, skippedCount, failureCount);
        } catch (Exception e) {
            log.error("执行服务器监控采集任务时发生错误", e);
        }
    }

    private MonitorCollectResult collectServerMonitorSnapshot(Server server) {
        try {
            return serverMonitorStatsService.collectAndSave(server) != null
                    ? MonitorCollectResult.SUCCESS
                    : MonitorCollectResult.SKIPPED;
        } catch (Exception e) {
            log.error("采集服务器监控失败，serverId={}, ip={}, error={}",
                    server.getId(), server.getIp(), e.getMessage(), e);
            return MonitorCollectResult.FAILED;
        }
    }

    private long getRefreshMinutes() {
        return Math.max(1L, systemConfigService.getLongValue("airopscat.server.monitor.refresh-minutes", 1L));
    }

    private enum MonitorCollectResult {
        SUCCESS,
        SKIPPED,
        FAILED
    }
}
