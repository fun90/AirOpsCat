package com.fun90.airopscat.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * 版本比较工具类
 */
public class VersionUtil {
    
    private VersionUtil() {
        throw new IllegalStateException("Utility class");
    }
    
    /**
     * 比较两个版本号
     * @param version1 版本1
     * @param version2 版本2
     * @return 0表示相等，-1表示version1小于version2，1表示version1大于version2
     */
    public static int compareVersion(String version1, String version2) {
        if (StringUtils.isBlank(version1) && StringUtils.isBlank(version2)) {
            return 0;
        }
        if (StringUtils.isBlank(version1)) {
            return -1;
        }
        if (StringUtils.isBlank(version2)) {
            return 1;
        }
        
        String[] v1Parts = version1.split("\\.");
        String[] v2Parts = version2.split("\\.");
        
        int maxLength = Math.max(v1Parts.length, v2Parts.length);
        
        for (int i = 0; i < maxLength; i++) {
            int v1Part = i < v1Parts.length ? parseVersionPart(v1Parts[i]) : 0;
            int v2Part = i < v2Parts.length ? parseVersionPart(v2Parts[i]) : 0;
            
            if (v1Part < v2Part) {
                return -1;
            } else if (v1Part > v2Part) {
                return 1;
            }
        }
        
        return 0;
    }
    
    /**
     * 解析版本号的一部分
     * @param part 版本号部分
     * @return 数字值
     */
    private static int parseVersionPart(String part) {
        if (StringUtils.isBlank(part)) {
            return 0;
        }
        
        // 移除非数字字符
        String cleanPart = part.replaceAll("[^0-9]", "");
        return StringUtils.isBlank(cleanPart) ? 0 : Integer.parseInt(cleanPart);
    }
} 