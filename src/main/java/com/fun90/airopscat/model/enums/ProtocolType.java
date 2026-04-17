package com.fun90.airopscat.model.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@Getter
public enum ProtocolType {
    VLESS("VLESS-Vision", "vless", 0, List.of("sing-box")),
    VLESS_REALITY("VLESS-Vision-REALITY", "vless-reality", 0, List.of("sing-box")),
    HYSTERIA2("Hysteria2", "hysteria2", 0, List.of("sing-box")),
    SHADOWTLS("ShadowTLS", "shadowtls", 0, List.of("sing-box")),
    SHADOWSOCKS("Shadowsocks", "shadowsocks", 1, List.of("sing-box")),
    SOCKS("SOCKS", "socks", 1, List.of("sing-box"));

    private final String label;
    private final String value;
    private final Integer type;
    private final List<String> coreTypes;

    ProtocolType(String label, String value, Integer type, List<String> coreTypes) {
        this.label = label;
        this.value = value;
        this.type = type;
        this.coreTypes = coreTypes;
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
        return type.equals(nodeType)
                && coreType != null
                && coreTypes.stream().anyMatch(item -> item.equalsIgnoreCase(coreType));
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
