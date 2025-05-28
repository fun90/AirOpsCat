package com.fun90.airopscat.config.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.StreamSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.*;
import com.fun90.airopscat.model.enums.ProtocolType;
import com.fun90.airopscat.model.enums.XrayProtocolType;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * InboundConfig的自定义反序列化器
 * 主要用于根据protocol字段正确反序列化settings字段
 */
@Slf4j
public class InboundConfigDeserializer extends JsonDeserializer<InboundConfig> {

    @Override
    public InboundConfig deserialize(JsonParser p, DeserializationContext ctxt) 
            throws IOException, JsonProcessingException {
        
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode rootNode = mapper.readTree(p);
        
        InboundConfig config = new InboundConfig();
        
        // 反序列化基本字段
        if (rootNode.has("tag")) {
            config.setTag(rootNode.get("tag").asText());
        }
        if (rootNode.has("listen")) {
            config.setListen(rootNode.get("listen").asText());
        }
        if (rootNode.has("port")) {
            config.setPort(rootNode.get("port").asInt());
        }
        
        String protocol = null;
        if (rootNode.has("protocol")) {
            protocol = rootNode.get("protocol").asText();
            config.setProtocol(protocol);
        }
        
        // 根据protocol反序列化settings
        if (rootNode.has("settings") && protocol != null) {
            JsonNode settingsNode = rootNode.get("settings");
            InboundSetting settings = deserializeSettings(mapper, settingsNode, protocol);
            config.setSettings(settings);
        }
        
        // 反序列化streamSettings
        if (rootNode.has("streamSettings")) {
            JsonNode streamNode = rootNode.get("streamSettings");
            StreamSetting streamSettings = mapper.treeToValue(streamNode, StreamSetting.class);
            config.setStreamSettings(streamSettings);
        }
        
        return config;
    }
    
    /**
     * 根据协议类型反序列化settings
     */
    private InboundSetting deserializeSettings(ObjectMapper mapper, JsonNode settingsNode, String protocol) {
        try {
            Class<? extends InboundSetting> targetClass = getSettingsClass(protocol);
            return mapper.treeToValue(settingsNode, targetClass);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize settings for protocol: {}, error: {}", protocol, e.getMessage());
            // 如果反序列化失败，返回默认的VlessInboundSetting
            try {
                return mapper.treeToValue(settingsNode, VlessInboundSetting.class);
            } catch (JsonProcessingException ex) {
                log.error("Failed to deserialize as default VlessInboundSetting", ex);
                return new VlessInboundSetting();
            }
        }
    }
    
    /**
     * 根据协议获取对应的Settings类
     */
    private Class<? extends InboundSetting> getSettingsClass(String protocol) {
        XrayProtocolType protocolType = XrayProtocolType.fromString(protocol);
        if (protocolType == null) {
            throw new IllegalArgumentException("Unknown protocol: " + protocol);
        }
        return switch (protocolType) {
            case DOCKODEMO_DOOR -> DokodemoDoorInboundSetting.class;
            case VLESS -> VlessInboundSetting.class;
            case SOCKS -> SocksInboundSetting.class;
            case SHADOWSOCKS -> ShadowsocksInboundSetting.class;
            default -> {
                log.warn("Unknown protocol: {}, using VlessInboundSetting as default", protocol);
                yield VlessInboundSetting.class;
            }
        };
    }
}