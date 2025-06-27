package com.fun90.airopscat.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.BarkNotificationDto;

import lombok.extern.slf4j.Slf4j;

/**
 * Bark通知服务
 * 用于通过HTTP请求发送通知到Bark服务器
 * 支持GET和POST两种请求方式
 * 参考Bark官方文档: https://github.com/Finb/Bark
 */
@Slf4j
@Service
public class BarkService {

    @Value("${airopscat.bark.url:}")
    private String barkUrl;

    @Value("${airopscat.bark.device-key:}")
    private String deviceKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public BarkService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 发送简单通知
     *
     * @param title 通知标题
     * @param body  通知内容
     * @return 是否发送成功
     */
    public boolean sendNotification(String title, String body) {
        return sendNotification(BarkNotificationDto.builder()
                .title(title)
                .body(body)
                .build());
    }

    /**
     * 发送通知
     *
     * @param notification 通知对象
     * @return 是否发送成功
     */
    public boolean sendNotification(BarkNotificationDto notification) {
        if (!isBarkConfigured()) {
            log.warn("Bark URL未配置，跳过通知发送");
            return false;
        }

        try {
            // 设置设备密钥
            if (notification.getDeviceKey() == null) {
                notification.setDeviceKey(deviceKey);
            }

            // 使用POST请求方式，符合Bark官方API规范
            URI requestUri = URI.create(barkUrl + "/" + notification.getDeviceKey());
            
            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            // 创建请求实体，发送JSON数据
            String jsonBody = objectMapper.writeValueAsString(notification);
            HttpEntity<String> requestEntity = new HttpEntity<>(jsonBody, headers);
            
            log.info("Bark POST请求URL: {}, 请求体: {}", requestUri, jsonBody);
            
            // 发送POST请求
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUri, 
                    HttpMethod.POST, 
                    requestEntity, 
                    String.class
            );
            
            log.info("Bark响应: {}", response.getBody());
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Bark通知发送成功: {}", notification.getTitle());
                return true;
            } else {
                log.error("Bark通知发送失败，状态码: {}", response.getStatusCode());
                return false;
            }
            
        } catch (Exception e) {
            log.error("发送Bark通知时发生异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 发送系统通知
     *
     * @param title   通知标题
     * @param body    通知内容
     * @param level   通知级别
     * @return 是否发送成功
     */
    public boolean sendSystemNotification(String title, String body, String level) {
        return sendNotification(BarkNotificationDto.builder()
                .title(title)
                .body(body)
                .level(level)
                .group("AirOpsCat")
                .sound("system")
                .build());
    }

    /**
     * 发送警告通知
     *
     * @param title 通知标题
     * @param body  通知内容
     * @return 是否发送成功
     */
    public boolean sendWarningNotification(String title, String body) {
        return sendSystemNotification(title, body, "timeSensitive");
    }

    /**
     * 发送错误通知
     *
     * @param title 通知标题
     * @param body  通知内容
     * @return 是否发送成功
     */
    public boolean sendErrorNotification(String title, String body) {
        return sendSystemNotification(title, body, "active");
    }

    /**
     * 发送信息通知
     *
     * @param title 通知标题
     * @param body  通知内容
     * @return 是否发送成功
     */
    public boolean sendInfoNotification(String title, String body) {
        return sendSystemNotification(title, body, "passive");
    }

    /**
     * 检查Bark是否已配置
     *
     * @return 是否已配置
     */
    public boolean isBarkConfigured() {
        return barkUrl != null && !barkUrl.trim().isEmpty() && 
               deviceKey != null && !deviceKey.trim().isEmpty();
    }

    /**
     * 获取Bark URL
     *
     * @return Bark URL
     */
    public String getBarkUrl() {
        return barkUrl;
    }

    /**
     * 获取设备密钥
     *
     * @return 设备密钥
     */
    public String getDeviceKey() {
        return deviceKey;
    }

    /**
     * 发送GET请求通知（备用方法）
     *
     * @param notification 通知对象
     * @return 是否发送成功
     */
    public boolean sendNotificationGet(BarkNotificationDto notification) {
        if (!isBarkConfigured()) {
            log.warn("Bark URL未配置，跳过通知发送");
            return false;
        }

        try {
            // 设置设备密钥
            if (notification.getDeviceKey() == null) {
                notification.setDeviceKey(deviceKey);
            }

            // 构建GET请求URI
            URI requestUri = buildRequestUri(notification);
            
            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            
            // 创建请求实体
            HttpEntity<String> requestEntity = new HttpEntity<>(headers);
            
            log.info("Bark GET请求URL: {}", requestUri);
            
            // 发送GET请求
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUri, 
                    HttpMethod.GET, 
                    requestEntity, 
                    String.class
            );
            
            log.info("Bark响应: {}", response.getBody());
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Bark通知发送成功: {}", notification.getTitle());
                return true;
            } else {
                log.error("Bark通知发送失败，状态码: {}", response.getStatusCode());
                return false;
            }
            
        } catch (Exception e) {
            log.error("发送Bark通知时发生异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 构建GET请求URI
     * 根据Bark官方URL结构: /:key/:body 或 /:key/:title/:body
     *
     * @param notification 通知对象
     * @return 请求URI
     */
    private URI buildRequestUri(BarkNotificationDto notification) {
        String key = notification.getDeviceKey();
        String title = notification.getTitle();
        String body = notification.getBody();
        
        // 使用UriComponentsBuilder避免双重编码
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(barkUrl)
                .path("/" + key);
        
        // 根据Bark官方URL结构构建路径
        if (title != null && !title.trim().isEmpty()) {
            builder.path("/" + title);
        }
        builder.path("/" + body);

        // 添加可选参数作为查询参数
        if (notification.getIcon() != null) {
            builder.queryParam("icon", notification.getIcon());
        }
        if (notification.getSound() != null) {
            builder.queryParam("sound", notification.getSound());
        }
        if (notification.getUrl() != null) {
            builder.queryParam("url", notification.getUrl());
        }
        if (notification.getGroup() != null) {
            builder.queryParam("group", notification.getGroup());
        }
        if (notification.getAutoCopy() != null && notification.getAutoCopy()) {
            builder.queryParam("autoCopy", "1");
        }
        if (notification.getCopy() != null) {
            builder.queryParam("copy", notification.getCopy());
        }
        if (notification.getLevel() != null) {
            builder.queryParam("level", notification.getLevel());
        }
        if (notification.getBadge() != null) {
            builder.queryParam("badge", notification.getBadge());
        }
        if (notification.getIsArchive() != null && notification.getIsArchive()) {
            builder.queryParam("isArchive", "1");
        }

        return builder.build().toUri();
    }
} 