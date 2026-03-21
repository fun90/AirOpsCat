package com.fun90.airopscat.model.enums;

import lombok.Getter;

@Getter
public enum ServerAuthType {
    PASSWORD("密码"),
    KEY("密钥");

    private final String description;

    ServerAuthType(String description) {
        this.description = description;
    }

}
