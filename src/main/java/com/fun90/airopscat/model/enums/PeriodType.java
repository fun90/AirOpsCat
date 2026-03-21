package com.fun90.airopscat.model.enums;

import lombok.Getter;

@Getter
public enum PeriodType {
    MONTHLY("每月"),
    YEARLY("每年");

    private final String description;

    PeriodType(String description) {
        this.description = description;
    }

}
