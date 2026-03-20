package com.fun90.airopscat.model.enums;

import lombok.Getter;

@Getter
public enum ProtocolType {
    VLESS("VLESS", "vless", 0),
    VLESS_REALITY("VLESS-REALITY", "vless-reality", 0),
    HYSTERIA2("Hysteria2", "hysteria2", 0),
    SOCKS("Socks", "socks", 1),
    SHADOWSOCKS("Shadowsocks", "shadowsocks", 1),
    SHADOWTLS("ShadowTLS", "shadowtls", 0);
    
    private final String label;
    private final String value;
    private final Integer type;

    ProtocolType(String label, String value, Integer type) {
        this.label = label;
        this.value = value;
        this.type = type;
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