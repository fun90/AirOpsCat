package com.fun90.airopscat.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 更新通知服务
 * 在Quarkus启动时检查最新版本并提示更新
 */
@ApplicationScoped
@Slf4j
public class UpdateNotificationService {

    @ConfigProperty(name = "app.version")
    String currentVersion;
    
    @ConfigProperty(name = "app.update-check.enabled", defaultValue = "false")
    boolean updateCheckEnabled;

    void onStart(@Observes StartupEvent event) {
        // 异步执行版本检查，避免阻塞应用启动
        new Thread(() -> {
            try {
                checkForUpdates();
            } catch (Exception e) {
                log.warn("版本检查失败: {}", e.getMessage());
            }
        }).start();
    }

    /**
     * 检查更新
     */
    private void checkForUpdates() {
        try {
            log.info("===========================================");
            log.info("欢迎使用 AirOpsCat");
            log.info("当前版本: v{}", currentVersion);
            
            if (!updateCheckEnabled) {
                log.info("在线版本检查已禁用");
                log.info("===========================================");
                return;
            }
            
            log.info("正在检查最新版本...");
            
            // TODO: 实现真正的HTTP客户端调用来检查更新
            // 当前为简化实现，仅显示当前版本信息
            log.info("在线版本检查功能正在开发中");
            log.info("如需启用此功能，请在 application.properties 中设置：");
            log.info("app.update-check.enabled=true");
            
            log.info("===========================================");
            
        } catch (Exception e) {
            log.error("检查更新时发生错误: {}", e.getMessage(), e);
        }
    }
} 