package com.fun90.airopscat.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

/**
 * 版本比较工具类
 */
@UtilityClass
@Slf4j
public class VersionUtil {
    
    /**
     * 比较两个版本号
     * 
     * @param currentVersion 当前版本 (例如: "2.0.4")
     * @param latestVersion 最新版本 (例如: "2.0.6")
     * @return 如果最新版本更高返回 true，否则返回 false
     */
    public static boolean isNewerVersion(String currentVersion, String latestVersion) {
        if (currentVersion == null || latestVersion == null) {
            return false;
        }
        
        try {
            // 清理版本号，移除可能的 'v' 前缀
            String cleanCurrent = cleanVersion(currentVersion);
            String cleanLatest = cleanVersion(latestVersion);
            
            // 分割版本号
            String[] currentParts = cleanCurrent.split("\\.");
            String[] latestParts = cleanLatest.split("\\.");
            
            // 确保两个版本号的长度一致，不足的用 0 补齐
            int maxLength = Math.max(currentParts.length, latestParts.length);
            currentParts = padVersionArray(currentParts, maxLength);
            latestParts = padVersionArray(latestParts, maxLength);
            
            // 逐个比较版本号的每一部分
            for (int i = 0; i < maxLength; i++) {
                int currentPart = parseVersionPart(currentParts[i]);
                int latestPart = parseVersionPart(latestParts[i]);
                
                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
                // 如果相等，继续比较下一部分
            }
            
            // 所有部分都相等
            return false;
            
        } catch (Exception e) {
            log.warn("版本号比较失败: current={}, latest={}, error={}", 
                    currentVersion, latestVersion, e.getMessage());
            return false;
        }
    }
    
    /**
     * 清理版本号，移除 'v' 前缀和其他非数字字符
     */
    private static String cleanVersion(String version) {
        if (version == null) {
            return "0";
        }
        
        // 移除 'v' 前缀
        String cleaned = version.toLowerCase().replaceFirst("^v", "");
        
        // 只保留数字和点号
        cleaned = cleaned.replaceAll("[^0-9.]", "");
        
        // 移除开头和结尾的点号
        cleaned = cleaned.replaceAll("^\\.|\\.$", "");
        
        return cleaned.isEmpty() ? "0" : cleaned;
    }
    
    /**
     * 补齐版本号数组长度
     */
    private static String[] padVersionArray(String[] versionParts, int targetLength) {
        if (versionParts.length >= targetLength) {
            return versionParts;
        }
        
        String[] padded = Arrays.copyOf(versionParts, targetLength);
        for (int i = versionParts.length; i < targetLength; i++) {
            padded[i] = "0";
        }
        return padded;
    }
    
    /**
     * 解析版本号的一部分为整数
     */
    private static int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    /**
     * 格式化版本号显示
     */
    public static String formatVersion(String version) {
        if (version == null || version.trim().isEmpty()) {
            return "未知";
        }
        
        String cleaned = cleanVersion(version);
        return cleaned.isEmpty() ? "未知" : "v" + cleaned;
    }
}