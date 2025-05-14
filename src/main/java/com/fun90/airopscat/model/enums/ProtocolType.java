package com.fun90.airopscat.model.enums;

public enum ProtocolType {
    VLESS("VLESS"),
    HYSTERIA2("Hysteria2"),
    SOCKS("Socks"),
    SHADOWSOCKS("Shadowsocks"),
    SHADOWTLS("ShadowTLS");
    
    private final String value;
    
    ProtocolType(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    public static ProtocolType fromString(String text) {
        for (ProtocolType type : ProtocolType.values()) {
            if (type.value.equalsIgnoreCase(text)) {
                return type;
            }
        }
        return null;
    }
}