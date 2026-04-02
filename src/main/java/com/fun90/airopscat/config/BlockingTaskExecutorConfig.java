package com.fun90.airopscat.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class BlockingTaskExecutorConfig {
    private static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    @ConfigProperty(name = "airopscat.thread.blocking.core-size", defaultValue = "4")
    int blockingCorePoolSize;

    @ConfigProperty(name = "airopscat.thread.blocking.max-size", defaultValue = "16")
    int blockingMaxPoolSize;

    @ConfigProperty(name = "airopscat.thread.blocking.queue-capacity", defaultValue = "128")
    int blockingQueueCapacity;

    @ConfigProperty(name = "airopscat.thread.blocking.keep-alive-seconds", defaultValue = "60")
    long blockingKeepAliveSeconds;

    @ConfigProperty(name = "airopscat.thread.deployment.core-size", defaultValue = "2")
    int deploymentCorePoolSize;

    @ConfigProperty(name = "airopscat.thread.deployment.max-size", defaultValue = "8")
    int deploymentMaxPoolSize;

    @ConfigProperty(name = "airopscat.thread.deployment.queue-capacity", defaultValue = "32")
    int deploymentQueueCapacity;

    @ConfigProperty(name = "airopscat.thread.deployment.keep-alive-seconds", defaultValue = "60")
    long deploymentKeepAliveSeconds;

    @ConfigProperty(name = "airopscat.thread.monitor.core-size", defaultValue = "4")
    int monitorCorePoolSize;

    @ConfigProperty(name = "airopscat.thread.monitor.max-size", defaultValue = "16")
    int monitorMaxPoolSize;

    @ConfigProperty(name = "airopscat.thread.monitor.queue-capacity", defaultValue = "128")
    int monitorQueueCapacity;

    @ConfigProperty(name = "airopscat.thread.monitor.keep-alive-seconds", defaultValue = "60")
    long monitorKeepAliveSeconds;

    @ConfigProperty(name = "airopscat.thread.backup.core-size", defaultValue = "1")
    int backupCorePoolSize;

    @ConfigProperty(name = "airopscat.thread.backup.max-size", defaultValue = "2")
    int backupMaxPoolSize;

    @ConfigProperty(name = "airopscat.thread.backup.queue-capacity", defaultValue = "8")
    int backupQueueCapacity;

    @ConfigProperty(name = "airopscat.thread.backup.keep-alive-seconds", defaultValue = "60")
    long backupKeepAliveSeconds;

    @Produces
    @ApplicationScoped
    @Named("blockingTaskExecutor")
    public ExecutorService blockingTaskExecutor() {
        return createExecutor("airopscat-blocking", blockingCorePoolSize, blockingMaxPoolSize,
                blockingQueueCapacity, blockingKeepAliveSeconds);
    }

    public void shutdown(@Disposes @Named("blockingTaskExecutor") ExecutorService executorService) {
        executorService.shutdown();
    }

    @Produces
    @ApplicationScoped
    @Named("deploymentTaskExecutor")
    public ExecutorService deploymentTaskExecutor() {
        return createExecutor("airopscat-deployment", deploymentCorePoolSize, deploymentMaxPoolSize,
                deploymentQueueCapacity, deploymentKeepAliveSeconds);
    }

    public void shutdownDeployment(@Disposes @Named("deploymentTaskExecutor") ExecutorService executorService) {
        executorService.shutdown();
    }

    @Produces
    @ApplicationScoped
    @Named("monitorTaskExecutor")
    public ExecutorService monitorTaskExecutor() {
        return createExecutor("airopscat-monitor", monitorCorePoolSize, monitorMaxPoolSize,
                monitorQueueCapacity, monitorKeepAliveSeconds);
    }

    public void shutdownMonitor(@Disposes @Named("monitorTaskExecutor") ExecutorService executorService) {
        executorService.shutdown();
    }

    @Produces
    @ApplicationScoped
    @Named("backupTaskExecutor")
    public ExecutorService backupTaskExecutor() {
        return createExecutor("airopscat-backup", backupCorePoolSize, backupMaxPoolSize,
                backupQueueCapacity, backupKeepAliveSeconds);
    }

    public void shutdownBackup(@Disposes @Named("backupTaskExecutor") ExecutorService executorService) {
        executorService.shutdown();
    }

    public static String describeExecutor(ExecutorService executorService) {
        if (!(executorService instanceof ThreadPoolExecutor executor)) {
            return "executorType=" + executorService.getClass().getSimpleName();
        }
        return "poolSize=" + executor.getPoolSize()
                + ", active=" + executor.getActiveCount()
                + ", queued=" + executor.getQueue().size()
                + ", completed=" + executor.getCompletedTaskCount();
    }

    private ExecutorService createExecutor(String threadPrefix, int corePoolSize, int maxPoolSize, int queueCapacity,
                                           long keepAliveSeconds) {
        int safeCorePoolSize = Math.max(1, corePoolSize);
        int safeMaxPoolSize = Math.max(safeCorePoolSize, maxPoolSize);
        int safeQueueCapacity = Math.max(1, queueCapacity);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                safeCorePoolSize,
                safeMaxPoolSize,
                Math.max(1L, keepAliveSeconds <= 0 ? DEFAULT_KEEP_ALIVE_SECONDS : keepAliveSeconds),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(safeQueueCapacity),
                new BlockingTaskThreadFactory(threadPrefix),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static class BlockingTaskThreadFactory implements ThreadFactory {
        private final String threadPrefix;

        private final AtomicInteger threadIndex = new AtomicInteger(1);

        private BlockingTaskThreadFactory(String threadPrefix) {
            this.threadPrefix = threadPrefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, threadPrefix + "-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
