package com.fun90.airopscat.model.enums;

public enum PeriodType {
    DAILY("每日"),
    WEEKLY("每周"),
    MONTHLY("每月"),
    CUSTOM("自定义");

    private final String description;

    PeriodType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static PeriodType fromString(String text) {
        for (PeriodType type : PeriodType.values()) {
            if (type.name().equalsIgnoreCase(text)) {
                return type;
            }
        }
        return CUSTOM; // 默认为自定义
    }
}