package com.fun90.airopscat.model.enums;

public enum ProtocolType {
    VLESS("VLESS", 0),
    HYSTERIA2("Hysteria2", 0),
    SOCKS("Socks", 1),
    SHADOWSOCKS("Shadowsocks", 1),
    SHADOWTLS("ShadowTLS", 0);
    
    private final String value;
    private final Integer type;

    ProtocolType(String value, Integer type) {
        this.value = value;
        this.type = type;
    }
    
    public String getValue() {
        return value;
    }

    public Integer getType() { return this.type; }
    
    public static ProtocolType fromString(String text) {
        for (ProtocolType type : ProtocolType.values()) {
            if (type.value.equalsIgnoreCase(text)) {
                return type;
            }
        }
        return null;
    }
}