package com.fun90.airopscat.model.enums;

public enum PeriodType {
    MONTHLY("每月"),
    YEARLY("每年");

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
        return MONTHLY; // 默认为每月
    }
}