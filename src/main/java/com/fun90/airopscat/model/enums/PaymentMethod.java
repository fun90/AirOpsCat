package com.fun90.airopscat.model.enums;

import lombok.Getter;

import java.util.Objects;

@Getter
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

    public static PaymentMethod fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (PaymentMethod type : PaymentMethod.values()) {
            if (Objects.equals(type.value, value)) {
                return type;
            }
        }
        return null;
    }
}