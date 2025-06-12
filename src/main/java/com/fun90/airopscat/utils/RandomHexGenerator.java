package com.fun90.airopscat.utils;

import java.security.SecureRandom;

public class RandomHexGenerator {
    private static final String HEX_CHARS = "0123456789abcdef";
    private static final SecureRandom random = new SecureRandom();

    public static String generateRandomHex() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(HEX_CHARS.charAt(random.nextInt(16)));
        }
        return sb.toString();
    }
    
    // 测试方法
    public static void main(String[] args) {
        System.out.println("方法1: " + generateRandomHex());
    }
}