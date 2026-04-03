package com.fun90.airopscat.model.enums;

import lombok.Getter;

@Getter
public enum NodeBatchTagUpdateMode {
    REPLACE("REPLACE", "覆盖"),
    APPEND("APPEND", "新增");

    private final String value;
    private final String description;

    NodeBatchTagUpdateMode(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static NodeBatchTagUpdateMode fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return REPLACE;
        }
        for (NodeBatchTagUpdateMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("不支持的批量标签调整模式");
    }
}
