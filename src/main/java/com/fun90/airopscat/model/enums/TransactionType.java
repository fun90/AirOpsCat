package com.fun90.airopscat.model.enums;

public enum TransactionType {
    INCOME(0, "收入"),
    EXPENSE(1, "支出");
    
    private final int value;
    private final String description;
    
    TransactionType(int value, String description) {
        this.value = value;
        this.description = description;
    }
    
    public int getValue() {
        return value;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static TransactionType fromValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (TransactionType type : TransactionType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }
}