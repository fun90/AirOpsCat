package com.fun90.airopscat.util;

import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GraalVM Native Image友好的随机工具类
 * 提供多种随机算法选择，避免静态初始化问题
 */
public class NativeRandomUtils {
    
    private static final String HEX_CHARS = "0123456789abcdef";
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    
    /**
     * 使用ThreadLocalRandom生成随机十六进制字符串（推荐）
     * ThreadLocalRandom对GraalVM native image最友好
     */
    public static String generateRandomHexFast(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(HEX_CHARS.charAt(random.nextInt(HEX_CHARS.length())));
        }
        return sb.toString();
    }
    
    /**
     * 使用SecureRandom生成密码学安全的随机十六进制字符串
     * 适用于安全要求较高的场景
     */
    public static String generateSecureRandomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        // 每次创建新实例，避免静态初始化问题
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < length; i++) {
            sb.append(HEX_CHARS.charAt(random.nextInt(HEX_CHARS.length())));
        }
        return sb.toString();
    }
    
    /**
     * 使用ThreadLocalRandom生成随机字母数字字符串
     */
    public static String generateRandomAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }
    
    /**
     * 使用System.nanoTime()和hashCode()的简单随机算法
     * 性能最高，但随机性较弱，适用于不需要高安全性的场景
     */
    public static String generateSimpleRandomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        long seed = System.nanoTime() ^ Thread.currentThread().getId();
        for (int i = 0; i < length; i++) {
            seed = seed * 1103515245L + 12345L; // Linear Congruential Generator
            int index = (int)((seed >>> 32) % HEX_CHARS.length());
            if (index < 0) index = -index;
            sb.append(HEX_CHARS.charAt(index));
        }
        return sb.toString();
    }
    
    /**
     * 从数组中随机选择一个元素
     */
    public static <T> T randomChoice(T[] array) {
        return array[ThreadLocalRandom.current().nextInt(array.length)];
    }
    
    /**
     * 生成指定范围内的随机整数
     */
    public static int randomInt(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max);
    }
    
    /**
     * 生成随机布尔值
     */
    public static boolean randomBoolean() {
        return ThreadLocalRandom.current().nextBoolean();
    }
}