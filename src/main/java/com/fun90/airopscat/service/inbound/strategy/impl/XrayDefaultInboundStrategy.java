package com.fun90.airopscat.service.inbound.strategy.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.DefaultConfigDto;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.service.inbound.strategy.DefaultInboundStrategy;
import com.fun90.airopscat.util.ConfigFileReader;
import com.fun90.airopscat.util.NativeRandomUtils;
import com.fun90.airopscat.util.TemplateUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@ApplicationScoped
@SupportedCores(value = {"xray"}, priority = 1, description = "Xray 默认入站配置生成策略")
public class XrayDefaultInboundStrategy implements DefaultInboundStrategy {

    private static final String[] DESTINATIONS = {"www.apple.com", "www.icloud.com", "www.amazon.com"};

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TemplateUtil templateUtil;

    @Inject
    ConfigFileReader configFileReader;

    @Override
    public DefaultConfigDto<Map<String, Object>> generateDefaultInbound(String protocol, Long serverId) {
        String normalizedProtocol = normalizeProtocol(protocol);
        String templatePath = getTemplatePath(normalizedProtocol);
        Map<String, Object> templateData = buildTemplateData(normalizedProtocol);
        String templateContent = configFileReader.readFileContent(templatePath);
        String renderedConfig = templateUtil.processStringTemplate(templateContent, templateData);

        try {
            Map<String, Object> config = objectMapper.readValue(renderedConfig, new TypeReference<>() {
            });

            DefaultConfigDto<Map<String, Object>> dto = new DefaultConfigDto<>();
            dto.setCoreType(CoreType.XRAY.getValue());
            dto.setProtocol(normalizedProtocol);
            dto.setConfig(config);
            return dto;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse default inbound config template", e);
        }
    }

    @Override
    public String getStrategyName() {
        return CoreType.XRAY.getValue();
    }

    private String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            throw new IllegalArgumentException("协议不能为空");
        }
        return protocol.trim().toLowerCase();
    }

    private String getTemplatePath(String protocol) {
        return switch (protocol) {
            case "vless" -> "config/core/xray-inbound-vless.json";
            case "vless-reality" -> "config/core/xray-inbound-vless-reality.json";
            case "shadowsocks" -> "config/core/xray-inbound-shadowsocks.json";
            case "socks" -> "config/core/xray-inbound-socks.json";
            default -> throw new UnsupportedOperationException("Xray 默认入站配置暂不支持协议: " + protocol);
        };
    }

    private Map<String, Object> buildTemplateData(String protocol) {
        Map<String, Object> templateData = new HashMap<>();

        switch (protocol) {
            case "vless":
                return templateData;
            case "vless-reality":
                String selectedDest = NativeRandomUtils.randomChoice(DESTINATIONS);
                String[] keys = generateX25519Keys();
                templateData.put("selectedDest", selectedDest);
                templateData.put("privateKey", keys[0]);
                templateData.put("publicKey", keys[1]);
                templateData.put("shortId", NativeRandomUtils.generateRandomHexFast(16));
                return templateData;
            case "shadowsocks":
                templateData.put("password", generateRandomPassword(20));
                templateData.put("email", generateRandomPassword(8));
                return templateData;
            case "socks":
                templateData.put("username", NativeRandomUtils.generateRandomHexFast(12));
                templateData.put("password", generateRandomPassword(20));
                return templateData;
            default:
                throw new UnsupportedOperationException("Xray 默认入站配置暂不支持协议: " + protocol);
        }
    }

    private String[] generateX25519Keys() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("xray", "x25519");
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            List<String> output = reader.lines().toList();
            process.waitFor();
            reader.close();

            String privateKey = "";
            String publicKey = "";
            for (String line : output) {
                String normalizedLine = line.trim();
                if (normalizedLine.startsWith("Private key:") || normalizedLine.startsWith("PrivateKey:")) {
                    privateKey = normalizedLine.substring(normalizedLine.indexOf(':') + 1).trim();
                } else if (normalizedLine.startsWith("Public key:") || normalizedLine.startsWith("PublicKey:")) {
                    publicKey = normalizedLine.substring(normalizedLine.indexOf(':') + 1).trim();
                }
            }

            if (!privateKey.isEmpty() && !publicKey.isEmpty()) {
                return new String[]{privateKey, publicKey};
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to generate X25519 keys", e);
        }

        return new String[]{"ABR3X0eLYM_6CRHTFepn7GrpSHEFCYqzGFaZ6Uj1L0E", "_bhnIqIPO2m2ov5JY3BTroTVPpZk40Xbf6WLlRCxASw"};
    }

    private String generateRandomPassword(int length) {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder password = new StringBuilder();
        Random random = new Random();

        for (int i = 0; i < length; i++) {
            password.append(characters.charAt(random.nextInt(characters.length())));
        }

        return password.toString();
    }
}
