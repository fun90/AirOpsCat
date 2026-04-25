package com.fun90.airopscat.model.enums;

import java.util.Arrays;

public enum NodeDeploymentStatus {
    PENDING_DEPLOY(0, "待部署"),
    DEPLOYED(1, "已部署"),
    PENDING_DELETE(2, "待删除");

    private final int value;
    private final String description;

    NodeDeploymentStatus(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public static NodeDeploymentStatus fromValue(Integer value) {
        if (value == null) {
            return PENDING_DEPLOY;
        }
        return Arrays.stream(values())
                .filter(status -> status.value == value)
                .findFirst()
                .orElse(PENDING_DEPLOY);
    }

    public static boolean isPendingDeploy(Integer value) {
        return fromValue(value) == PENDING_DEPLOY;
    }

    public static boolean isDeployed(Integer value) {
        return fromValue(value) == DEPLOYED;
    }

    public static boolean isPendingDelete(Integer value) {
        return fromValue(value) == PENDING_DELETE;
    }
}
