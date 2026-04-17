package com.fun90.airopscat.model.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@Getter
public enum ProtocolType {
    VLESS("VLESS-Vision", "vless", 0),
    VLESS_REALITY("VLESS-Vision-REALITY", "vless-reality", 0),
    HYSTERIA2("Hysteria2", "hysteria2", 0),
    SHADOWTLS("ShadowTLS", "shadowtls", 0),
    SHADOWSOCKS("Shadowsocks", "shadowsocks", 1),
    SOCKS("SOCKS", "socks", 1);

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

    public boolean supports(Integer nodeType, String coreType) {
        return type.equals(nodeType);
    }

    public static boolean isSupported(String protocol, Integer nodeType, String coreType) {
        ProtocolType protocolType = fromString(protocol);
        return protocolType != null && protocolType.supports(nodeType, coreType);
    }

    public static List<ProtocolType> getSupportedProtocols(Integer nodeType, String coreType) {
        return Arrays.stream(ProtocolType.values())
                .filter(protocolType -> protocolType.supports(nodeType, coreType))
                .toList();
    }
}
