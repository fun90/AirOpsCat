package com.fun90.airopscat.service;

import com.fun90.airopscat.client.GitHubApiClient;
import com.fun90.airopscat.config.AppConstants;
import com.fun90.airopscat.config.SshSmokeTestMode;
import com.fun90.airopscat.model.dto.GitHubReleaseDto;
import com.fun90.airopscat.util.VersionUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 更新通知服务
 * 在Quarkus启动时检查最新版本并提示更新
 */
@ApplicationScoped
@Slf4j
public class UpdateNotificationService {

    @Inject
    @RestClient
    GitHubApiClient gitHubApiClient;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    @Inject
    @Named("blockingTaskExecutor")
    ExecutorService blockingTaskExecutor;

    void onStart(@Observes StartupEvent event) {
        if (SshSmokeTestMode.isEnabled()) {
            log.info("SSH smoke test mode enabled, skipping update notification");
            return;
        }

        // 异步执行版本检查，避免阻塞应用启动
        CompletableFuture.runAsync(this::checkForUpdates, blockingTaskExecutor)
                .exceptionally(throwable -> {
                    log.warn("版本检查失败: {}", throwable.getMessage());
                    return null;
                });
    }

    /**
     * 检查更新
     */
    private void checkForUpdates() {
        try {
            printWelcomeMessage();

            if (!AppConstants.UPDATE_CHECK_ENABLED) {
                log.info("在线版本检查已禁用");
                printSeparator();
                return;
            }

            log.info("正在检查最新版本...");

            // 调用 GitHub API 检查最新版本
            GitHubReleaseDto latestRelease = gitHubApiClient.getLatestRelease()
                    .toCompletableFuture()
                    .orTimeout(AppConstants.UPDATE_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();

            handleVersionCheck(latestRelease);

        } catch (Exception e) {
            handleVersionCheckError(e);
        }
    }

    /**
     * 打印欢迎信息
     */
    private void printWelcomeMessage() {
        log.info("===========================================");
        log.info("🐱 欢迎使用 AirOpsCat");
        log.info("当前版本: {}", VersionUtil.formatVersion(appVersion));
    }

    /**
     * 打印分隔线
     */
    private void printSeparator() {
        log.info("===========================================");
    }

    /**
     * 处理版本检查结果
     */
    private void handleVersionCheck(GitHubReleaseDto latestRelease) {
        if (latestRelease == null || latestRelease.getVersion() == null) {
            log.warn("无法获取最新版本信息");
            printSeparator();
            return;
        }

        String latestVersion = latestRelease.getVersion();
        log.info("最新版本: {}", VersionUtil.formatVersion(latestVersion));

        if (VersionUtil.isNewerVersion(appVersion, latestVersion)) {
            printUpdateAvailable(latestRelease);
        } else {
            log.info("✅ 您使用的是最新版本");
        }

        printSeparator();
    }

    /**
     * 打印更新可用信息
     */
    private void printUpdateAvailable(GitHubReleaseDto latestRelease) {
        log.info("🚀 发现新版本可用!");
        log.info("新版本: {}", VersionUtil.formatVersion(latestRelease.getVersion()));

        if (latestRelease.getName() != null && !latestRelease.getName().trim().isEmpty()) {
            log.info("版本名称: {}", latestRelease.getName());
        }

        if (latestRelease.getPublishedAt() != null) {
            log.info("发布时间: {}", latestRelease.getPublishedAt());
        }

        if (latestRelease.getHtmlUrl() != null) {
            log.info("下载地址: {}", latestRelease.getHtmlUrl());
        }

        // 显示发布说明 (限制长度避免日志过长)
        if (latestRelease.getBody() != null && !latestRelease.getBody().trim().isEmpty()) {
            String releaseNotes = latestRelease.getBody().trim();
            if (releaseNotes.length() > 200) {
                releaseNotes = releaseNotes.substring(0, 200) + "...";
            }
            log.info("发布说明: {}", releaseNotes);
        }
    }

    /**
     * 处理版本检查错误
     */
    private void handleVersionCheckError(Exception e) {
        if (e instanceof java.util.concurrent.TimeoutException) {
            log.warn("版本检查超时，请检查网络连接");
        } else if (e instanceof java.net.ConnectException) {
            log.warn("无法连接到 GitHub API，请检查网络连接");
        } else {
            log.warn("版本检查失败: {}", e.getMessage());
            log.debug("版本检查详细错误信息", e);
        }

        log.info("您可以手动访问以下地址检查更新:");
        log.info("https://github.com/fun90/AirOpsCat/releases");
        printSeparator();
    }

}
