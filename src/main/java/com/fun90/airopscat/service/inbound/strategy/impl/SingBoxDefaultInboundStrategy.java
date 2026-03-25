package com.fun90.airopscat.service.inbound.strategy.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.annotation.SupportedCores;
import com.fun90.airopscat.model.dto.DefaultConfigDto;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.model.entity.ServerHost;
import com.fun90.airopscat.service.ServerHostService;
import com.fun90.airopscat.service.ServerService;
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
import java.util.UUID;

@Slf4j
@ApplicationScoped
@SupportedCores(value = {"sing-box"}, priority = 1, description = "Sing-box default inbound strategy")
public class SingBoxDefaultInboundStrategy implements DefaultInboundStrategy {

    private static final String[] SERVER_NAMES = {"www.apple.com", "www.icloud.com", "www.amazon.com"};

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TemplateUtil templateUtil;

    @Inject
    ConfigFileReader configFileReader;

    @Inject
    ServerService serverService;

    @Inject
    ServerHostService serverHostService;

    @Override
    public DefaultConfigDto<Map<String, Object>> generateDefaultInbound(String protocol, Long serverId, Long accessHostId) {
        String normalizedProtocol = normalizeProtocol(protocol);
        String templatePath = getTemplatePath(normalizedProtocol);
        Map<String, Object> templateData = buildTemplateData(normalizedProtocol, serverId, accessHostId);
        String templateContent = configFileReader.readFileContent(templatePath);
        String renderedConfig = templateUtil.processStringTemplate(templateContent, templateData);

        try {
            Map<String, Object> config = objectMapper.readValue(renderedConfig, new TypeReference<>() {
            });

            DefaultConfigDto<Map<String, Object>> dto = new DefaultConfigDto<>();
            dto.setCoreType(CoreType.SING_BOX.getValue());
            dto.setProtocol(normalizedProtocol);
            dto.setConfig(config);
            return dto;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse sing-box default inbound config template", e);
        }
    }

    @Override
    public String getStrategyName() {
        return CoreType.SING_BOX.getValue();
    }

    private String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            throw new IllegalArgumentException("Protocol cannot be empty");
        }
        return protocol.trim().toLowerCase();
    }

    private String getTemplatePath(String protocol) {
        return switch (protocol) {
            case "vless" -> "config/core/sing-box-inbound-vless.json";
            case "vless-reality" -> "config/core/sing-box-inbound-vless-reality.json";
            case "hysteria2" -> "config/core/sing-box-inbound-hysteria2.json";
            case "shadowtls" -> "config/core/sing-box-inbound-shadowtls.json";
            case "shadowsocks" -> "config/core/sing-box-inbound-shadowsocks.json";
            case "socks" -> "config/core/sing-box-inbound-socks.json";
            default -> throw new UnsupportedOperationException("Sing-box default inbound does not support protocol: " + protocol);
        };
    }

    private Map<String, Object> buildTemplateData(String protocol, Long serverId, Long accessHostId) {
        Map<String, Object> templateData = new HashMap<>();
        String selectedServerName = NativeRandomUtils.randomChoice(SERVER_NAMES);
        ServerHost accessHost = serverHostService.getHostById(accessHostId);
        String serverName = accessHost != null && accessHost.getHost() != null && !accessHost.getHost().isBlank()
                ? accessHost.getHost().trim()
                : serverService.getServerHostById(serverId);

        switch (protocol) {
            case "vless":
                templateData.put("uuid", UUID.randomUUID().toString());
                templateData.put("serverName", serverName);
                return templateData;
            case "vless-reality":
                String[] keys = generateRealityKeyPair();
                templateData.put("uuid", UUID.randomUUID().toString());
                templateData.put("serverName", selectedServerName);
                templateData.put("privateKey", keys[0]);
                templateData.put("publicKey", keys[1]);
                templateData.put("shortId", NativeRandomUtils.generateRandomHexFast(16));
                return templateData;
            case "hysteria2":
                templateData.put("password", NativeRandomUtils.generateRandomAlphanumeric(20));
                templateData.put("serverName", serverName);
                return templateData;
            case "shadowtls":
                templateData.put("password", NativeRandomUtils.generateRandomAlphanumeric(20));
                templateData.put("serverName", selectedServerName);
                return templateData;
            case "shadowsocks":
                templateData.put("email", NativeRandomUtils.generateRandomAlphanumeric(12));
                templateData.put("password", NativeRandomUtils.generateRandomAlphanumeric(20));
                return templateData;
            case "socks":
                templateData.put("username", "user_" + NativeRandomUtils.generateRandomAlphanumeric(12));
                templateData.put("password", NativeRandomUtils.generateRandomAlphanumeric(20));
                return templateData;
            default:
                throw new UnsupportedOperationException("Sing-box default inbound does not support protocol: " + protocol);
        }
    }

    private String[] generateRealityKeyPair() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("sing-box", "generate", "reality-keypair");
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            List<String> output = reader.lines().toList();
            process.waitFor();
            reader.close();

            String privateKey = "";
            String publicKey = "";
            for (String line : output) {
                String normalizedLine = line.trim();
                if (normalizedLine.startsWith("PrivateKey:") || normalizedLine.startsWith("Private key:")) {
                    privateKey = normalizedLine.substring(normalizedLine.indexOf(':') + 1).trim();
                } else if (normalizedLine.startsWith("PublicKey:") || normalizedLine.startsWith("Public key:")) {
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
            log.error("Failed to generate sing-box reality key pair", e);
        }

        return new String[]{"ABR3X0eLYM_6CRHTFepn7GrpSHEFCYqzGFaZ6Uj1L0E", "_bhnIqIPO2m2ov5JY3BTroTVPpZk40Xbf6WLlRCxASw"};
    }
}
