package com.fun90.airopscat.model.enums;

public enum PaymentMethod {
    WeChat("WeChat", "微信"),
    AliPay("AliPay", "支付宝"),
    BankCard("BankCard", "银行卡");

    private final String value;
    private final String description;

    PaymentMethod(String value, String description) {
        this.value = value;
        this.description = description;
    }
    
    public String getValue() {
        return value;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static PaymentMethod fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (PaymentMethod type : PaymentMethod.values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }
}