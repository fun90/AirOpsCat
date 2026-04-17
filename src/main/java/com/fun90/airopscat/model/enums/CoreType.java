package com.fun90.airopscat.model.enums;

import lombok.Getter;

@Getter
public enum CoreType {
    HYSTERIA2("hysteria2", "hysteria2"),

    SING_BOX("sing-box", "sing-box");


    private final String value;
    private final String name;

    CoreType(String value, String name) {
        this.value = value;
        this.name = name;
    }

    public static CoreType fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        for (CoreType type : CoreType.values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }

}
