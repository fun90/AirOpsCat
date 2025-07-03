package com.fun90.airopscat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.utils.VersionUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 更新通知服务
 * 在Spring Boot启动时检查最新版本并提示更新
 */
@Service
@PropertySource("internal.properties")
@Slf4j
public class UpdateNotificationService implements CommandLineRunner {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.githubURL}")
    private String githubURL;

    @Value("${app.version}")
    private String currentVersion;

    @Override
    public void run(String... args) throws Exception {
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
            log.info("正在检查最新版本...");
            
            JsonNode response = restTemplate.getForObject(githubURL, JsonNode.class);
            if (response == null) {
                log.warn("无法获取GitHub API响应");
                return;
            }

            String tagName = response.get("tag_name").asText();
            if (StringUtils.isBlank(tagName)) {
                log.warn("无法获取最新版本标签");
                return;
            }

            // 移除版本号前缀 "v"
            String latestVersion = StringUtils.removeStartIgnoreCase(tagName, "v");
            
            // 获取更新说明
            String body = response.get("body").asText();
            if (StringUtils.isNotBlank(body)) {
                body = body.replaceAll("#", "");
            }

            log.info("===========================================");
            log.info("欢迎使用 AirOpsCat");
            
            int comparison = VersionUtil.compareVersion(currentVersion, latestVersion);
            
            if (comparison == 0) {
                log.info("当前运行的是最新版本: v{}", latestVersion);
            } else if (comparison == -1) {
                log.info("当前版本: v{}，可更新至最新版本：v{}", currentVersion, latestVersion);
                if (StringUtils.isNotBlank(body)) {
                    log.info("更新内容：{}", body);
                }
            } else {
                log.info("当前版本: v{} (开发版本)", currentVersion);
            }
            log.info("===========================================");
            
        } catch (Exception e) {
            log.error("检查更新时发生错误: {}", e.getMessage(), e);
        }
    }
} 