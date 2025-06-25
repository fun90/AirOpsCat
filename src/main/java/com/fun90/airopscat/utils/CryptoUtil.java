package com.fun90.airopscat.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 加密工具类
 * 用于处理敏感信息（如服务器认证信息）的加密和解密
 */
@Component
public class CryptoUtil {
    
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    
    // 默认密钥，在生产环境中应该从配置文件或环境变量中读取
    @Value("${airopscat.crypto.secret-key:AirOpsCatDefaultSecretKey2024}")
    private String secretKey;
    
    /**
     * 生成AES密钥
     * @return Base64编码的密钥字符串
     */
    public static String generateSecretKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(ALGORITHM);
            keyGenerator.init(256, new SecureRandom());
            SecretKey secretKey = keyGenerator.generateKey();
            return Base64.getEncoder().encodeToString(secretKey.getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成密钥失败", e);
        }
    }
    
    /**
     * 获取密钥规格
     * @param key 密钥字符串
     * @return SecretKeySpec
     */
    private SecretKeySpec getSecretKeySpec(String key) {
        // 确保密钥长度为32字节（256位）
        byte[] keyBytes = new byte[32];
        byte[] originalKey = key.getBytes();
        System.arraycopy(originalKey, 0, keyBytes, 0, Math.min(originalKey.length, keyBytes.length));
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
    
    /**
     * 加密字符串
     * @param plainText 明文
     * @return 加密后的Base64编码字符串
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.trim().isEmpty()) {
            return plainText;
        }
        
        try {
            SecretKeySpec secretKeySpec = getSecretKeySpec(secretKey);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec);
            
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes());
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            throw new RuntimeException("加密失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解密字符串
     * @param encryptedText 加密的Base64编码字符串
     * @return 解密后的明文
     */
    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.trim().isEmpty()) {
            return encryptedText;
        }
        
        try {
            SecretKeySpec secretKeySpec = getSecretKeySpec(secretKey);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec);
            
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedText);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes);
        } catch (Exception e) {
            // 如果解密失败，可能是未加密的明文，直接返回原文
            // 这样可以兼容已存在的明文数据
            return encryptedText;
        }
    }
    
    /**
     * 检查字符串是否已加密
     * @param text 要检查的文本
     * @return true如果是加密文本，false如果是明文
     */
    public boolean isEncrypted(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        try {
            // 尝试解密，如果成功且结果不同，说明是加密的
            String decrypted = decrypt(text);
            return !text.equals(decrypted);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 安全地比较加密后的数据
     * @param encryptedText 加密的文本
     * @param plainText 明文
     * @return 是否匹配
     */
    public boolean matches(String encryptedText, String plainText) {
        if (encryptedText == null && plainText == null) {
            return true;
        }
        if (encryptedText == null || plainText == null) {
            return false;
        }
        
        try {
            String decrypted = decrypt(encryptedText);
            return plainText.equals(decrypted);
        } catch (Exception e) {
            return false;
        }
    }
} 