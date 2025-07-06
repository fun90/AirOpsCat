package com.fun90.airopscat.model.enums;

public enum ServerAuthType {
    PASSWORD("密码"),
    KEY("密钥");
    
    private final String description;
    
    ServerAuthType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static ServerAuthType fromString(String text) {
        for (ServerAuthType type : ServerAuthType.values()) {
            if (type.name().equalsIgnoreCase(text)) {
                return type;
            }
        }
        return PASSWORD; // 默认为密码
    }
}