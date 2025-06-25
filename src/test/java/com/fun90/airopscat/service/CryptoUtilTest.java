package com.fun90.airopscat.service;

import com.fun90.airopscat.utils.CryptoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加密工具类测试
 */
public class CryptoUtilTest {

    private CryptoUtil cryptoUtil;

    @BeforeEach
    void setUp() {
        cryptoUtil = new CryptoUtil();
        // 设置测试密钥
        ReflectionTestUtils.setField(cryptoUtil, "secretKey", "TestSecretKey2024");
    }

    @Test
    void testEncryptDecrypt() {
        String plainText = "testPassword123";
        
        // 加密
        String encrypted = cryptoUtil.encrypt(plainText);
        assertNotNull(encrypted);
        assertNotEquals(plainText, encrypted);
        
        // 解密
        String decrypted = cryptoUtil.decrypt(encrypted);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testEncryptEmptyString() {
        // 测试空字符串
        assertNull(cryptoUtil.encrypt(null));
        assertEquals("", cryptoUtil.encrypt(""));
        assertEquals("   ", cryptoUtil.encrypt("   "));
    }

    @Test
    void testDecryptEmptyString() {
        // 测试空字符串
        assertNull(cryptoUtil.decrypt(null));
        assertEquals("", cryptoUtil.decrypt(""));
        assertEquals("   ", cryptoUtil.decrypt("   "));
    }

    @Test
    void testIsEncrypted() {
        String plainText = "testPassword123";
        String encrypted = cryptoUtil.encrypt(plainText);
        
        assertFalse(cryptoUtil.isEncrypted(plainText));
        assertTrue(cryptoUtil.isEncrypted(encrypted));
        assertFalse(cryptoUtil.isEncrypted(null));
        assertFalse(cryptoUtil.isEncrypted(""));
    }

    @Test
    void testMatches() {
        String plainText = "testPassword123";
        String encrypted = cryptoUtil.encrypt(plainText);
        
        assertTrue(cryptoUtil.matches(encrypted, plainText));
        assertFalse(cryptoUtil.matches(encrypted, "wrongPassword"));
        assertTrue(cryptoUtil.matches(null, null));
        assertFalse(cryptoUtil.matches(encrypted, null));
        assertFalse(cryptoUtil.matches(null, plainText));
    }

    @Test
    void testLongText() {
        String longText = "This is a very long password with special characters: !@#$%^&*()_+-=[]{}|;':\",./<>?`~";
        
        String encrypted = cryptoUtil.encrypt(longText);
        String decrypted = cryptoUtil.decrypt(encrypted);
        
        assertEquals(longText, decrypted);
    }

    @Test
    void testSpecialCharacters() {
        String specialText = "密码123!@#$%^&*()";
        
        String encrypted = cryptoUtil.encrypt(specialText);
        String decrypted = cryptoUtil.decrypt(encrypted);
        
        assertEquals(specialText, decrypted);
    }

    @Test
    void testGenerateSecretKey() {
        String key1 = CryptoUtil.generateSecretKey();
        String key2 = CryptoUtil.generateSecretKey();
        
        assertNotNull(key1);
        assertNotNull(key2);
        assertNotEquals(key1, key2);
        // Base64编码的32字节密钥应该是44个字符
        assertTrue(key1.length() > 40);
        assertTrue(key2.length() > 40);
    }
} 