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

    @ConfigProperty(name = "airopscat.thread.blocking.core-size", defaultValue = "2")
    int corePoolSize;

    @ConfigProperty(name = "airopscat.thread.blocking.max-size", defaultValue = "8")
    int maxPoolSize;

    @ConfigProperty(name = "airopscat.thread.blocking.queue-capacity", defaultValue = "64")
    int queueCapacity;

    @ConfigProperty(name = "airopscat.thread.blocking.keep-alive-seconds", defaultValue = "60")
    long keepAliveSeconds;

    @Produces
    @ApplicationScoped
    @Named("blockingTaskExecutor")
    public ExecutorService blockingTaskExecutor() {
        int safeCorePoolSize = Math.max(1, corePoolSize);
        int safeMaxPoolSize = Math.max(safeCorePoolSize, maxPoolSize);
        int safeQueueCapacity = Math.max(1, queueCapacity);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                safeCorePoolSize,
                safeMaxPoolSize,
                Math.max(1, keepAliveSeconds),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(safeQueueCapacity),
                new BlockingTaskThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public void shutdown(@Disposes @Named("blockingTaskExecutor") ExecutorService executorService) {
        executorService.shutdown();
    }

    private static class BlockingTaskThreadFactory implements ThreadFactory {

        private final AtomicInteger threadIndex = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "airopscat-blocking-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
