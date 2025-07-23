package com.fun90.airopscat.util;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 错误信息编码工具
 * 用于在URL中安全传递错误信息
 */
@Slf4j
@ApplicationScoped
public class ErrorMessageEncoder {

    /**
     * 编码错误信息用于URL传递
     * 使用URL安全的Base64编码，避免+和/字符问题
     */
    public static String encode(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        
        try {
            // 使用URL安全的Base64编码（用-和_替代+和/）
            // URL安全的Base64不需要再进行URL编码
            return Base64.getUrlEncoder().encodeToString(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 如果编码失败，返回简单的错误标识
            return "encoding_error";
        }
    }

    /**
     * 解码URL中的错误信息
     * 使用URL安全的Base64解码，不需要处理+和/字符问题
     */
    public static String decode(String encodedMessage) {
        if (encodedMessage == null || encodedMessage.isEmpty()) {
            return "";
        }
        
        if ("encoding_error".equals(encodedMessage)) {
            return "消息编码错误";
        }
        
        try {
            // 直接使用URL安全的Base64解码
            return new String(Base64.getUrlDecoder().decode(encodedMessage), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "消息解码失败";
        }
    }
}