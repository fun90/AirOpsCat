package com.fun90.airopscat.model.enums;

public enum NodeType {
    PROXY(0, "代理"),
    LANDING(1, "落地");
    
    private final int value;
    private final String description;
    
    NodeType(int value, String description) {
        this.value = value;
        this.description = description;
    }
    
    public int getValue() {
        return value;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static NodeType fromValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (NodeType type : NodeType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }
}