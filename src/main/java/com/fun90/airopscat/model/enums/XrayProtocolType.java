package com.fun90.airopscat.model.enums;

public enum XrayProtocolType {
    DOCKODEMO_DOOR("Dokodemo-Door"),

    VLESS("VLESS"),

    SHADOWSOCKS("Shadowsocks"),

    SOCKS("Socks"),

    BLACKHOLE("Blackhole"),

    FREEDOM("Freedom");

    private final String value;

    private XrayProtocolType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static XrayProtocolType fromString(String text) {
        for (XrayProtocolType type : XrayProtocolType.values()) {
            if (type.getValue().equalsIgnoreCase(text)) {
                return type;
            }
        }
        return null;
    }
}
