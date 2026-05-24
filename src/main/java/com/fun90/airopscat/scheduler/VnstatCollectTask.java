package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.ServerVnstatStatsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@ApplicationScoped
public class VnstatCollectTask {

    @Inject
    ServerRepository serverRepository;

    @Inject
    ServerVnstatStatsService serverVnstatStatsService;

    @Inject
    @Named("monitorTaskExecutor")
    ExecutorService monitorTaskExecutor;

    public void collectVnstatTraffic() {
        long startedAt = System.nanoTime();
        log.info("开始执行定时任务：采集服务器 vnstat 流量");

        try {
            List<Server> servers = serverRepository.findMonitorableServers(LocalDateTime.now().toLocalDate());
            if (servers.isEmpty()) {
                log.info("没有找到可监控服务器，跳过 vnstat 采集");
                return;
            }

            List<CompletableFuture<Void>> futures = servers.stream()
                    .map(server -> CompletableFuture.runAsync(
                            () -> serverVnstatStatsService.collectFromServer(server),
                            monitorTaskExecutor))
                    .toList();

            futures.forEach(f -> {
                try {
                    f.join();
                } catch (Exception e) {
                    log.error("vnstat 采集异常: {}", e.getMessage(), e);
                }
            });

            log.info("vnstat 流量采集完成，服务器数: {}, 耗时: {} ms",
                    servers.size(), (System.nanoTime() - startedAt) / 1_000_000);
        } catch (Exception e) {
            log.error("执行 vnstat 采集任务时发生错误", e);
        }
    }
}
