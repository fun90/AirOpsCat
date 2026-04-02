package com.fun90.airopscat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.BarkNotificationDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class BarkService {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SystemConfigService systemConfigService;

    private final Client client = ClientBuilder.newClient();

    public String getBarkUrl() {
        return resolveBarkConfig(null).barkUrl();
    }

    public String getDeviceKey() {
        return resolveBarkConfig(null).deviceKey();
    }

    public boolean sendNotification(String title, String body) {
        return sendNotification(BarkNotificationDto.builder()
                .title(title)
                .body(body)
                .build());
    }

    public boolean sendNotification(BarkNotificationDto notification) {
        return sendNotificationInternal(notification, null);
    }

    public boolean sendNotificationWithOverrides(String title, String body, Map<String, String> overrides) {
        return sendNotificationInternal(BarkNotificationDto.builder()
                .title(title)
                .body(body)
                .build(), overrides);
    }

    public boolean sendSystemNotification(String title, String body, String level) {
        BarkResolvedConfig config = resolveBarkConfig(null);
        return sendNotification(BarkNotificationDto.builder()
                .title(title)
                .body(body)
                .level(level)
                .group(config.defaultGroup())
                .sound(config.defaultSound())
                .icon(config.defaultIcon())
                .build());
    }

    public boolean sendWarningNotification(String title, String body) {
        return sendSystemNotification(title, body, "timeSensitive");
    }

    public boolean sendErrorNotification(String title, String body) {
        return sendSystemNotification(title, body, "active");
    }

    public boolean sendInfoNotification(String title, String body) {
        return sendSystemNotification(title, body, "passive");
    }

    public boolean isBarkConfigured() {
        return isBarkConfigured(resolveBarkConfig(null));
    }

    public boolean sendNotificationGet(BarkNotificationDto notification) {
        BarkResolvedConfig config = resolveBarkConfig(null);
        if (!isBarkConfigured(config)) {
            log.warn("Bark is not configured, skipping notification");
            return false;
        }

        BarkNotificationDto request = prepareNotification(notification, config);
        if (request == null) {
            log.warn("Bark notification payload is empty, skipping notification");
            return false;
        }

        try {
            URI requestUri = buildRequestUri(request, config);
            log.info("Bark GET request URL: {}", requestUri);

            WebTarget target = client.target(requestUri);
            Response response = target.request(MediaType.APPLICATION_JSON).get();
            try {
                String responseBody = response.readEntity(String.class);
                log.info("Bark GET response: {}", responseBody);

                if (response.getStatus() >= 200 && response.getStatus() < 300) {
                    log.info("Bark GET notification sent successfully: {}", request.getTitle());
                    return true;
                }

                log.error("Bark GET notification failed, status: {}", response.getStatus());
                return false;
            } finally {
                response.close();
            }
        } catch (Exception e) {
            log.error("Failed to send Bark GET notification: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean sendNotificationInternal(BarkNotificationDto notification, Map<String, String> overrides) {
        BarkResolvedConfig config = resolveBarkConfig(overrides);
        if (!isBarkConfigured(config)) {
            log.warn("Bark is not configured, skipping notification");
            return false;
        }

        BarkNotificationDto request = prepareNotification(notification, config);
        if (request == null) {
            log.warn("Bark notification payload is empty, skipping notification");
            return false;
        }

        try {
            return shouldUseEncryptedPush(config)
                    ? sendEncryptedNotification(request, config)
                    : sendPlainNotification(request, config);
        } catch (Exception e) {
            log.error("Failed to send Bark notification: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean sendPlainNotification(BarkNotificationDto notification, BarkResolvedConfig config) throws Exception {
        URI requestUri = URI.create(config.barkUrl() + "/" + notification.getDeviceKey());
        String jsonBody = objectMapper.writeValueAsString(notification);

        log.info("Sending Bark JSON notification to {}", requestUri);

        WebTarget target = client.target(requestUri);
        Response response = target.request(MediaType.APPLICATION_JSON)
                .post(Entity.entity(jsonBody, MediaType.APPLICATION_JSON_TYPE));
        try {
            String responseBody = response.readEntity(String.class);
            log.info("Bark response: {}", responseBody);

            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                log.info("Bark notification sent successfully: {}", notification.getTitle());
                return true;
            }

            log.error("Bark notification failed, status: {}", response.getStatus());
            return false;
        } finally {
            response.close();
        }
    }

    private boolean sendEncryptedNotification(BarkNotificationDto notification, BarkResolvedConfig config) throws Exception {
        validateEncryptionConfig(config);

        URI requestUri = URI.create(config.barkUrl() + "/" + notification.getDeviceKey());
        String payload = objectMapper.writeValueAsString(notification);
        String ciphertext = encryptPayload(payload, config);

        Form form = new Form()
                .param("ciphertext", ciphertext)
                .param("iv", config.encryptionIv());

        log.info("Sending encrypted Bark notification to {}", requestUri);

        WebTarget target = client.target(requestUri);
        Response response = target.request(MediaType.APPLICATION_JSON)
                .post(Entity.entity(form, MediaType.APPLICATION_FORM_URLENCODED_TYPE));
        try {
            String responseBody = response.readEntity(String.class);
            log.info("Bark encrypted response: {}", responseBody);

            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                log.info("Encrypted Bark notification sent successfully: {}", notification.getTitle());
                return true;
            }

            log.error("Encrypted Bark notification failed, status: {}", response.getStatus());
            return false;
        } finally {
            response.close();
        }
    }

    private BarkNotificationDto prepareNotification(BarkNotificationDto notification, BarkResolvedConfig config) {
        if (notification == null || !hasText(notification.getBody()) && !hasText(notification.getTitle())) {
            return null;
        }

        return BarkNotificationDto.builder()
                .deviceKey(hasText(notification.getDeviceKey()) ? notification.getDeviceKey() : config.deviceKey())
                .title(notification.getTitle())
                .body(notification.getBody())
                .icon(firstNonBlank(notification.getIcon(), config.defaultIcon()))
                .sound(firstNonBlank(notification.getSound(), config.defaultSound()))
                .url(notification.getUrl())
                .group(firstNonBlank(notification.getGroup(), config.defaultGroup()))
                .autoCopy(notification.getAutoCopy())
                .copy(notification.getCopy())
                .level(notification.getLevel())
                .badge(notification.getBadge())
                .isArchive(notification.getIsArchive())
                .category(notification.getCategory())
                .threadId(notification.getThreadId())
                .priority(notification.getPriority())
                .timeout(notification.getTimeout())
                .actionable(notification.getActionable())
                .actions(notification.getActions())
                .build();
    }

    private boolean shouldUseEncryptedPush(BarkResolvedConfig config) {
        return config.encryptionEnabled()
                || hasText(config.encryptionKey())
                || hasText(config.encryptionIv());
    }

    private void validateEncryptionConfig(BarkResolvedConfig config) {
        if (!hasText(config.encryptionKey()) || !hasText(config.encryptionIv())) {
            throw new IllegalStateException("Bark encryption requires both key and iv");
        }

        int keyLength = config.encryptionKey().getBytes(StandardCharsets.UTF_8).length;
        int ivLength = config.encryptionIv().getBytes(StandardCharsets.UTF_8).length;
        if (keyLength != 32 || ivLength != 16) {
            throw new IllegalStateException("Bark encryption requires a 32-byte key and 16-byte iv");
        }
    }

    private String encryptPayload(String payload, BarkResolvedConfig config) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                config.encryptionKey().getBytes(StandardCharsets.UTF_8),
                "AES"
        );
        IvParameterSpec ivParameterSpec = new IvParameterSpec(
                config.encryptionIv().getBytes(StandardCharsets.UTF_8)
        );
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
        byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private URI buildRequestUri(BarkNotificationDto notification, BarkResolvedConfig config) {
        UriBuilder builder = UriBuilder.fromUri(config.barkUrl())
                .path(notification.getDeviceKey());

        if (hasText(notification.getTitle())) {
            builder.path(notification.getTitle());
        }
        builder.path(notification.getBody());

        if (hasText(notification.getIcon())) {
            builder.queryParam("icon", notification.getIcon());
        }
        if (hasText(notification.getSound())) {
            builder.queryParam("sound", notification.getSound());
        }
        if (hasText(notification.getUrl())) {
            builder.queryParam("url", notification.getUrl());
        }
        if (hasText(notification.getGroup())) {
            builder.queryParam("group", notification.getGroup());
        }
        if (Boolean.TRUE.equals(notification.getAutoCopy())) {
            builder.queryParam("autoCopy", "1");
        }
        if (hasText(notification.getCopy())) {
            builder.queryParam("copy", notification.getCopy());
        }
        if (hasText(notification.getLevel())) {
            builder.queryParam("level", notification.getLevel());
        }
        if (notification.getBadge() != null) {
            builder.queryParam("badge", notification.getBadge());
        }
        if (Boolean.TRUE.equals(notification.getIsArchive())) {
            builder.queryParam("isArchive", "1");
        }

        return builder.build();
    }

    private BarkResolvedConfig resolveBarkConfig(Map<String, String> overrides) {
        return new BarkResolvedConfig(
                resolveValue("airopscat.bark.url", overrides),
                resolveValue("airopscat.bark.device-key", overrides),
                Boolean.parseBoolean(resolveValue("airopscat.bark.encrypt-enabled", overrides)),
                resolveValue("airopscat.bark.encrypt-key", overrides),
                resolveValue("airopscat.bark.encrypt-iv", overrides),
                resolveValue("airopscat.bark.default-group", overrides),
                resolveValue("airopscat.bark.default-sound", overrides),
                resolveValue("airopscat.bark.default-icon", overrides)
        );
    }

    private String resolveValue(String key, Map<String, String> overrides) {
        if (overrides != null && overrides.containsKey(key)) {
            return normalize(overrides.get(key));
        }

        String value = systemConfigService.getResolvedValue(key);
        if (hasText(value) || isBooleanText(value)) {
            return value;
        }
        return null;
    }

    private boolean isBarkConfigured(BarkResolvedConfig config) {
        return hasText(config.barkUrl()) && hasText(config.deviceKey());
    }

    private String firstNonBlank(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private boolean isBooleanText(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record BarkResolvedConfig(
            String barkUrl,
            String deviceKey,
            boolean encryptionEnabled,
            String encryptionKey,
            String encryptionIv,
            String defaultGroup,
            String defaultSound,
            String defaultIcon
    ) {
    }
}
