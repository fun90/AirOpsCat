package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.dto.ScheduledTaskDto;
import com.fun90.airopscat.service.DatabaseBackupService;
import com.fun90.airopscat.service.SystemConfigService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ApplicationScoped
public class ProgrammaticTaskManager {

    private static final String SHANGHAI_TIME_ZONE = "Asia/Shanghai";
    private static final ZoneId SHANGHAI_ZONE_ID = ZoneId.of(SHANGHAI_TIME_ZONE);
    private static final String SCHEDULE_TYPE_CRON = "cron";
    private static final String SCHEDULE_TYPE_INTERVAL_MINUTES = "interval-minutes";
    private static final String SCHEDULE_TYPE_INTERVAL_HOURS = "interval-hours";

    private final Scheduler scheduler;
    private final SystemConfigService systemConfigService;
    private final DatabaseBackupService databaseBackupService;
    private final AccountExpirationTask accountExpirationTask;
    private final ResourceNotificationTask resourceNotificationTask;
    private final TrafficStatsTask trafficStatsTask;
    private final CoreConfigCleanupTask coreConfigCleanupTask;
    private final ServerMonitorStatsCleanupTask serverMonitorStatsCleanupTask;
    private final ServerMonitorTask serverMonitorTask;
    private final NodeDeploymentHistoryCleanupTask nodeDeploymentHistoryCleanupTask;
    private final AccountTrafficStatsCleanupTask accountTrafficStatsCleanupTask;
    private final ServerTrafficStatsCleanupTask serverTrafficStatsCleanupTask;
    private final SystemRequestLogCleanupTask systemRequestLogCleanupTask;
    private final AccountOnlineRefreshTask accountOnlineRefreshTask;
    private final AccountOnlineIpCleanupTask accountOnlineIpCleanupTask;
    private final NodeOnlineAccountStatsTask nodeOnlineAccountStatsTask;

    private final Map<String, TaskDefinition> taskDefinitions;
    private final Set<String> pausedTaskKeys;

    @Inject
    public ProgrammaticTaskManager(Scheduler scheduler,
                                   SystemConfigService systemConfigService,
                                   DatabaseBackupService databaseBackupService,
                                   AccountExpirationTask accountExpirationTask,
                                   ResourceNotificationTask resourceNotificationTask,
                                   TrafficStatsTask trafficStatsTask,
                                   CoreConfigCleanupTask coreConfigCleanupTask,
                                   ServerMonitorStatsCleanupTask serverMonitorStatsCleanupTask,
                                   ServerMonitorTask serverMonitorTask,
                                   NodeDeploymentHistoryCleanupTask nodeDeploymentHistoryCleanupTask,
                                   AccountTrafficStatsCleanupTask accountTrafficStatsCleanupTask,
                                   ServerTrafficStatsCleanupTask serverTrafficStatsCleanupTask,
                                   SystemRequestLogCleanupTask systemRequestLogCleanupTask,
                                   AccountOnlineRefreshTask accountOnlineRefreshTask,
                                   AccountOnlineIpCleanupTask accountOnlineIpCleanupTask,
                                   NodeOnlineAccountStatsTask nodeOnlineAccountStatsTask) {
        this.scheduler = scheduler;
        this.systemConfigService = systemConfigService;
        this.databaseBackupService = databaseBackupService;
        this.accountExpirationTask = accountExpirationTask;
        this.resourceNotificationTask = resourceNotificationTask;
        this.trafficStatsTask = trafficStatsTask;
        this.coreConfigCleanupTask = coreConfigCleanupTask;
        this.serverMonitorStatsCleanupTask = serverMonitorStatsCleanupTask;
        this.serverMonitorTask = serverMonitorTask;
        this.nodeDeploymentHistoryCleanupTask = nodeDeploymentHistoryCleanupTask;
        this.accountTrafficStatsCleanupTask = accountTrafficStatsCleanupTask;
        this.serverTrafficStatsCleanupTask = serverTrafficStatsCleanupTask;
        this.systemRequestLogCleanupTask = systemRequestLogCleanupTask;
        this.accountOnlineRefreshTask = accountOnlineRefreshTask;
        this.accountOnlineIpCleanupTask = accountOnlineIpCleanupTask;
        this.nodeOnlineAccountStatsTask = nodeOnlineAccountStatsTask;
        this.taskDefinitions = buildTaskDefinitions();
        this.pausedTaskKeys = ConcurrentHashMap.newKeySet();
    }

    void onStart(@Observes StartupEvent event) {
        reloadAllTasks();
    }

    public void reloadAllTasks() {
        if (!scheduler.isStarted()) {
            log.warn("Scheduler 尚未启动，跳过定时任务注册");
            return;
        }
        taskDefinitions.values().forEach(this::scheduleTask);
    }

    public void reloadTasksByGroup(String groupKey) {
        if (!scheduler.isStarted()) {
            log.warn("Scheduler 尚未启动，跳过分组定时任务重载: {}", groupKey);
            return;
        }
        taskDefinitions.values().stream()
                .filter(definition -> Objects.equals(definition.groupKey(), groupKey))
                .forEach(this::scheduleTask);
    }

    public List<ScheduledTaskDto> listTasks() {
        return taskDefinitions.values().stream()
                .sorted((left, right) -> Integer.compare(left.sortOrder(), right.sortOrder()))
                .map(this::toDto)
                .toList();
    }

    public ScheduledTaskDto runTask(String taskKey) {
        TaskDefinition definition = requireTaskDefinition(taskKey);
        log.info("开始手动执行定时任务: {}", definition.taskName());
        definition.task().run();
        return toDto(definition);
    }

    public ScheduledTaskDto pauseTask(String taskKey) {
        TaskDefinition definition = requireTaskDefinition(taskKey);
        if (!scheduler.isStarted()) {
            throw new IllegalStateException("Scheduler 尚未启动，无法暂停定时任务");
        }
        pausedTaskKeys.add(definition.taskKey());
        scheduler.unscheduleJob(definition.identity());
        log.info("定时任务已暂停: {}", definition.taskName());
        return toDto(definition);
    }

    public ScheduledTaskDto resumeTask(String taskKey) {
        TaskDefinition definition = requireTaskDefinition(taskKey);
        if (!scheduler.isStarted()) {
            throw new IllegalStateException("Scheduler 尚未启动，无法恢复定时任务");
        }
        pausedTaskKeys.remove(definition.taskKey());
        scheduleTask(definition);
        log.info("定时任务已恢复: {}", definition.taskName());
        return toDto(definition);
    }

    private void scheduleTask(TaskDefinition definition) {
        scheduler.unscheduleJob(definition.identity());

        if (pausedTaskKeys.contains(definition.taskKey())) {
            log.info("定时任务保持暂停状态: {}", definition.taskName());
            return;
        }

        String cron = resolveCron(definition);
        Scheduler.JobDefinition jobDefinition = scheduler.newJob(definition.identity())
                .setCron(cron)
                .setTimeZone(SHANGHAI_TIME_ZONE)
                .setTask(execution -> definition.task().run());

        if (definition.concurrentExecution() != null) {
            jobDefinition.setConcurrentExecution(definition.concurrentExecution());
        }

        jobDefinition.schedule();
        log.info("定时任务已注册: {} [{}]", definition.taskName(), cron);
    }

    private ScheduledTaskDto toDto(TaskDefinition definition) {
        Trigger trigger = scheduler.isStarted() ? scheduler.getScheduledJob(definition.identity()) : null;
        return ScheduledTaskDto.builder()
                .taskKey(definition.taskKey())
                .taskName(definition.taskName())
                .description(definition.description())
                .groupKey(definition.groupKey())
                .groupTitle(definition.groupTitle())
                .scheduleType(definition.scheduleType())
                .scheduleValue(resolveScheduleValue(definition))
                .paused(pausedTaskKeys.contains(definition.taskKey()))
                .scheduled(trigger != null)
                .overdue(trigger != null && trigger.isOverdue())
                .previousFireTime(toLocalDateTime(trigger == null ? null : trigger.getPreviousFireTime()))
                .nextFireTime(toLocalDateTime(trigger == null ? null : trigger.getNextFireTime()))
                .sortOrder(definition.sortOrder())
                .build();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, SHANGHAI_ZONE_ID);
    }

    private String resolveScheduleValue(TaskDefinition definition) {
        if (Objects.equals(SCHEDULE_TYPE_INTERVAL_MINUTES, definition.scheduleType())) {
            return Math.max(1L, systemConfigService.getLongValue(definition.configKey(), definition.defaultIntervalMinutes())) + " 分钟";
        }
        if (Objects.equals(SCHEDULE_TYPE_INTERVAL_HOURS, definition.scheduleType())) {
            return Math.max(1L, systemConfigService.getLongValue(definition.configKey(), definition.defaultIntervalMinutes())) + " 小时";
        }
        return resolveCron(definition);
    }

    private String resolveCron(TaskDefinition definition) {
        if (Objects.equals(SCHEDULE_TYPE_INTERVAL_MINUTES, definition.scheduleType())) {
            long refreshMinutes = Math.max(1L,
                    systemConfigService.getLongValue(definition.configKey(), definition.defaultIntervalMinutes()));
            return "0 */" + refreshMinutes + " * * * ?";
        }
        if (Objects.equals(SCHEDULE_TYPE_INTERVAL_HOURS, definition.scheduleType())) {
            long hours = Math.max(1L,
                    systemConfigService.getLongValue(definition.configKey(), definition.defaultIntervalMinutes()));
            return "0 0 */" + hours + " * * ?";
        }
        return systemConfigService.getResolvedValue(definition.configKey());
    }

    private TaskDefinition requireTaskDefinition(String taskKey) {
        TaskDefinition definition = taskDefinitions.get(taskKey);
        if (definition == null) {
            throw new IllegalArgumentException("不支持的定时任务: " + taskKey);
        }
        return definition;
    }

    private Map<String, TaskDefinition> buildTaskDefinitions() {
        Map<String, TaskDefinition> definitions = new LinkedHashMap<>();

        definitions.put("backup-create", task(
                "backup-create",
                "backup-create",
                "数据库备份",
                "按数据库备份配置生成压缩备份文件。",
                "backup",
                "数据库备份",
                10,
                SCHEDULE_TYPE_CRON,
                "airopscat.backup.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                databaseBackupService::scheduledBackup
        ));
        definitions.put("backup-cleanup", task(
                "backup-cleanup",
                "backup-cleanup",
                "备份清理",
                "按保留天数清理过期数据库备份文件。",
                "backup",
                "数据库备份",
                20,
                SCHEDULE_TYPE_CRON,
                "airopscat.backup.cleanup.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                databaseBackupService::cleanupExpiredBackups
        ));
        definitions.put("server-monitor-collect", task(
                "server-monitor-collect",
                "server-monitor-collect",
                "服务器监控采集",
                "按监控采集间隔收集服务器负载与流量指标。",
                "monitor",
                "监控与在线状态",
                30,
                SCHEDULE_TYPE_INTERVAL_MINUTES,
                "airopscat.server.monitor.refresh-minutes",
                1L,
                Scheduled.ConcurrentExecution.SKIP,
                serverMonitorTask::collectServerMonitorStats
        ));
        definitions.put("account-online-refresh", task(
                "account-online-refresh",
                "account-online-refresh",
                "在线账号刷新",
                "通过 Clash API 主动采集各服务器当前连接，刷新账号在线状态。",
                "monitor",
                "监控与在线状态",
                35,
                SCHEDULE_TYPE_INTERVAL_MINUTES,
                "airopscat.account.online.refresh-minutes",
                1L,
                Scheduled.ConcurrentExecution.SKIP,
                accountOnlineRefreshTask::refreshOnlineAccounts
        ));
        definitions.put("account-online-ip-cleanup", task(
                "account-online-ip-cleanup",
                "account-online-ip-cleanup",
                "在线连接记录清理",
                "按保留小时数清理历史在线连接记录，执行间隔与保留时长相同。",
                "monitor",
                "监控与在线状态",
                37,
                SCHEDULE_TYPE_INTERVAL_HOURS,
                "airopscat.account.online.history.retention-hours",
                8L,
                Scheduled.ConcurrentExecution.SKIP,
                accountOnlineIpCleanupTask::cleanupOldRecords
        ));
        definitions.put("node-online-account-stats", task(
                "node-online-account-stats",
                "node-online-account-stats",
                "节点在线账户日统计",
                "按当前在线窗口采样节点在线账户数，沉淀为每日趋势数据。",
                "monitor",
                "监控与在线状态",
                38,
                SCHEDULE_TYPE_INTERVAL_MINUTES,
                "airopscat.node.online-account.stats.sample-minutes",
                5L,
                Scheduled.ConcurrentExecution.SKIP,
                nodeOnlineAccountStatsTask::sampleDailyStats
        ));
        definitions.put("server-monitor-cleanup", task(
                "server-monitor-cleanup",
                "server-monitor-cleanup",
                "监控数据清理",
                "清理过期服务器监控历史数据。",
                "monitor",
                "监控与在线状态",
                40,
                SCHEDULE_TYPE_CRON,
                "airopscat.server.monitor.cleanup.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                serverMonitorStatsCleanupTask::cleanupExpiredServerMonitorStats
        ));
        definitions.put("server-monitor-alert", task(
                "server-monitor-alert",
                "server-monitor-alert",
                "监控负载提醒",
                "按监控告警配置检查服务器负载阈值并推送通知。",
                "monitor",
                "监控与在线状态",
                50,
                SCHEDULE_TYPE_CRON,
                "airopscat.server.monitor.alert.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                resourceNotificationTask::notifyServerMonitorLoad
        ));
        definitions.put("server-traffic-notify", task(
                "server-traffic-notify",
                "server-traffic-notify",
                "流量阈值提醒",
                "按流量阈值提醒配置发送服务器流量告警。",
                "monitor",
                "监控与在线状态",
                60,
                SCHEDULE_TYPE_CRON,
                "airopscat.server.traffic.notify.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                resourceNotificationTask::notifyServerTrafficThreshold
        ));
        definitions.put("account-expiration", task(
                "account-expiration",
                "account-expiration",
                "过期账号处理",
                "检查过期账号并重新部署关联节点。",
                "scheduled",
                "定时任务",
                70,
                SCHEDULE_TYPE_CRON,
                "airopscat.account.expiration.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                accountExpirationTask::checkExpiredAccountsAndRedeployNodes
        ));
        definitions.put("resource-expiration-notify", task(
                "resource-expiration-notify",
                "resource-expiration-notify",
                "资源到期提醒",
                "按配置发送账号、服务器、域名到期提醒。",
                "scheduled",
                "定时任务",
                80,
                SCHEDULE_TYPE_CRON,
                "airopscat.expiration.notify.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                resourceNotificationTask::notifyExpiringResourcesToday
        ));
        definitions.put("traffic-stats-collect", task(
                "traffic-stats-collect",
                "traffic-stats-collect",
                "流量统计采集",
                "采集账号和服务器流量统计数据。",
                "scheduled",
                "定时任务",
                90,
                SCHEDULE_TYPE_CRON,
                "airopscat.traffic.stats.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                trafficStatsTask::collectUserTrafficStats
        ));
        definitions.put("core-config-cleanup", task(
                "core-config-cleanup",
                "core-config-cleanup",
                "内核配置清理",
                "清理服务器上的旧内核配置备份文件。",
                "scheduled",
                "定时任务",
                110,
                SCHEDULE_TYPE_CRON,
                "airopscat.core.config.cleanup.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                coreConfigCleanupTask::cleanupOldCoreConfigBackupFiles
        ));
        definitions.put("node-deployment-history-cleanup", task(
                "node-deployment-history-cleanup",
                "node-deployment-history-cleanup",
                "部署历史清理",
                "按保留策略清理过期节点部署历史。",
                "scheduled",
                "定时任务",
                120,
                SCHEDULE_TYPE_CRON,
                "airopscat.node.deployment.history.cleanup.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                nodeDeploymentHistoryCleanupTask::cleanupExpiredHistory
        ));
        definitions.put("account-traffic-cleanup", task(
                "account-traffic-cleanup",
                "account-traffic-cleanup",
                "账户流量清理",
                "按保留策略清理过期账户流量明细。",
                "scheduled",
                "定时任务",
                130,
                SCHEDULE_TYPE_CRON,
                "airopscat.account.traffic.cleanup.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                accountTrafficStatsCleanupTask::cleanupExpiredStats
        ));
        definitions.put("server-traffic-cleanup", task(
                "server-traffic-cleanup",
                "server-traffic-cleanup",
                "服务器流量清理",
                "按保留策略清理过期服务器流量明细。",
                "scheduled",
                "定时任务",
                140,
                SCHEDULE_TYPE_CRON,
                "airopscat.server.traffic.cleanup.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                serverTrafficStatsCleanupTask::cleanupExpiredStats
        ));
        definitions.put("system-request-log-cleanup", task(
                "system-request-log-cleanup",
                "system-request-log-cleanup",
                "系统请求日志清理",
                "按保留天数清理过期系统请求日志。",
                "scheduled",
                "定时任务",
                145,
                SCHEDULE_TYPE_CRON,
                "airopscat.request-log.cleanup.cron",
                0L,
                Scheduled.ConcurrentExecution.SKIP,
                systemRequestLogCleanupTask::cleanupExpiredLogs
        ));

        return definitions;
    }

    private TaskDefinition task(String taskKey,
                                String identity,
                                String taskName,
                                String description,
                                String groupKey,
                                String groupTitle,
                                int sortOrder,
                                String scheduleType,
                                String configKey,
                                long defaultIntervalMinutes,
                                Scheduled.ConcurrentExecution concurrentExecution,
                                Runnable task) {
        return new TaskDefinition(taskKey, identity, taskName, description, groupKey, groupTitle, sortOrder,
                scheduleType, configKey, defaultIntervalMinutes, concurrentExecution, task);
    }

    private record TaskDefinition(
            String taskKey,
            String identity,
            String taskName,
            String description,
            String groupKey,
            String groupTitle,
            int sortOrder,
            String scheduleType,
            String configKey,
            long defaultIntervalMinutes,
            Scheduled.ConcurrentExecution concurrentExecution,
            Runnable task
    ) {
    }
}
