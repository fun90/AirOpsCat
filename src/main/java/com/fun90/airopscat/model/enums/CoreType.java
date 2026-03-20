package com.fun90.airopscat.model.enums;

import lombok.Getter;

@Getter
public enum CoreType {
    XRAY("xray", "xray"),

    HYSTERIA2("hysteria2", "hysteria2"),

    SING_BOX("sing-box", "sing-box");


    private final String value;
    private final String name;

    CoreType(String value, String name) {
        this.value = value;
        this.name = name;
    }

}
