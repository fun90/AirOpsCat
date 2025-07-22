package com.fun90.airopscat.util;

/**
 * 随机十六进制字符串生成器
 * 兼容GraalVM Native Image
 * 
 * @deprecated 推荐使用 NativeRandomUtils.generateRandomHexFast() 替代
 */
public class RandomHexGenerator {
    
    /**
     * 生成随机十六进制字符串
     * 使用ThreadLocalRandom确保GraalVM Native Image兼容性
     */
    public static String generateRandomHex(int length) {
        return NativeRandomUtils.generateRandomHexFast(length);
    }
}