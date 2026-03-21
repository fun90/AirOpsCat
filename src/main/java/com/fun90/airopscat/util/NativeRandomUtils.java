package com.fun90.airopscat.util;

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
     * 从数组中随机选择一个元素
     */
    public static <T> T randomChoice(T[] array) {
        return array[ThreadLocalRandom.current().nextInt(array.length)];
    }

}
