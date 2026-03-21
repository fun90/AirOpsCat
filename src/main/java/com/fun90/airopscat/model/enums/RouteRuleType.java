package com.fun90.airopscat.model.enums;

import lombok.Getter;

@Getter
public enum RouteRuleType {
    DOMAIN("domain", "域名", "domain", "domain"),
    IP("ip", "目标 IP", "ip", "ip_cidr"),
    SOURCE_IP("source_ip", "源 IP", "source", "source_ip_cidr"),
    PORT("port", "端口", "port", "port"),
    NETWORK("network", "网络", "network", "network"),
    INBOUND("inbound", "入口标签", "inboundTag", "inbound"),
    CUSTOM("custom", "自定义", null, null);

    private final String value;
    private final String description;
    private final String xrayField;
    private final String singBoxField;

    RouteRuleType(String value, String description, String xrayField, String singBoxField) {
        this.value = value;
        this.description = description;
        this.xrayField = xrayField;
        this.singBoxField = singBoxField;
    }

    public static RouteRuleType fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        for (RouteRuleType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
