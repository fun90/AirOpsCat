package com.fun90.airopscat.demo;

import com.fun90.airopscat.utils.CryptoUtil;

/**
 * 加密功能演示
 */
public class CryptoDemo {

    public static void main(String[] args) {
        // 创建加密工具实例
        CryptoUtil cryptoUtil = new CryptoUtil();
        
        // 设置密钥（通过反射，仅用于演示）
        try {
            var field = CryptoUtil.class.getDeclaredField("secretKey");
            field.setAccessible(true);
            field.set(cryptoUtil, "DemoSecretKey2024");
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        
        // 演示加密解密
        System.out.println("=== AirOpsCat 服务器认证信息加密演示 ===\n");
        
        // 密码示例
        String password = "mySecurePassword123";
        System.out.println("原始密码: " + password);
        
        String encryptedPassword = cryptoUtil.encrypt(password);
        System.out.println("加密后: " + encryptedPassword);
        
        String decryptedPassword = cryptoUtil.decrypt(encryptedPassword);
        System.out.println("解密后: " + decryptedPassword);
        
        System.out.println("密码验证: " + password.equals(decryptedPassword));
        System.out.println();
        
        // SSH 私钥示例
        String privateKey = "-----BEGIN PRIVATE KEY-----\n" +
                "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC...\n" +
                "-----END PRIVATE KEY-----";
        
        System.out.println("原始私钥 (前50字符): " + privateKey.substring(0, 50) + "...");
        
        String encryptedKey = cryptoUtil.encrypt(privateKey);
        System.out.println("加密后私钥 (前50字符): " + encryptedKey.substring(0, 50) + "...");
        
        String decryptedKey = cryptoUtil.decrypt(encryptedKey);
        System.out.println("解密后私钥 (前50字符): " + decryptedKey.substring(0, 50) + "...");
        
        System.out.println("私钥验证: " + privateKey.equals(decryptedKey));
        System.out.println();
        
        // 检测加密状态
        System.out.println("=== 加密状态检测 ===");
        System.out.println("明文密码是否已加密: " + cryptoUtil.isEncrypted(password));
        System.out.println("密文密码是否已加密: " + cryptoUtil.isEncrypted(encryptedPassword));
        
        // 验证功能
        System.out.println("\n=== 验证功能 ===");
        System.out.println("密码匹配验证: " + cryptoUtil.matches(encryptedPassword, password));
        System.out.println("错误密码验证: " + cryptoUtil.matches(encryptedPassword, "wrongPassword"));
        
        System.out.println("\n演示完成！");
        System.out.println("\n在实际应用中，JPA 转换器会自动处理加密解密过程，");
        System.out.println("业务代码只需要操作明文数据即可。");
    }
} 