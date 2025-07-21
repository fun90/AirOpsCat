package com.fun90.airopscat.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置文件读取工具类
 * 支持从 classpath 读取配置文件并转换为对象或JSON字符串
 */
@Slf4j
public final class ConfigFileReader {
    
    private ConfigFileReader() {
        throw new IllegalStateException("Utility class");
    }
    
    // 缓存已读取的配置文件内容，避免重复读取
    private static final Map<String, String> FILE_CONTENT_CACHE = new HashMap<>();
    
    /**
     * 从 classpath 读取配置文件内容
     * 
     * @param path 文件路径，相对于 classpath 根目录
     * @return 文件内容字符串
     * @throws RuntimeException 如果文件读取失败
     */
    public static String readFileContent(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }
        
        // 检查缓存
        if (FILE_CONTENT_CACHE.containsKey(path)) {
            log.debug("Reading file content from cache: {}", path);
            return FILE_CONTENT_CACHE.get(path);
        }
        
        try {
            // 确保路径不以 classpath: 开头（因为我们直接使用 getResourceAsStream）
            String resourcePath = path.startsWith("classpath:") ? path.substring(10) : path;
            
            // 使用 ClassLoader 读取资源
            InputStream inputStream = ConfigFileReader.class.getClassLoader().getResourceAsStream(resourcePath);
            
            if (inputStream == null) {
                throw new IllegalArgumentException("Configuration file not found: " + path);
            }
            
            String content;
            try (InputStream is = inputStream) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            
            // 缓存文件内容
            FILE_CONTENT_CACHE.put(path, content);
            log.debug("Successfully read configuration file: {}", path);
            
            return content;
        } catch (IOException e) {
            log.error("Failed to read configuration file: {}", path, e);
            throw new RuntimeException("Failed to read configuration file: " + path, e);
        }
    }

    
    /**
     * 检查配置文件是否存在
     * 
     * @param path 文件路径
     * @return true 如果文件存在，false 否则
     */
    public static boolean exists(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        
        try {
            String resourcePath = path.startsWith("classpath:") ? path.substring(10) : path;
            InputStream inputStream = ConfigFileReader.class.getClassLoader().getResourceAsStream(resourcePath);
            
            if (inputStream != null) {
                inputStream.close();
                return true;
            }
            return false;
        } catch (Exception e) {
            log.debug("Error checking file existence: {}", path, e);
            return false;
        }
    }
    
    /**
     * 清空文件内容缓存
     * 在开发环境或需要重新加载配置时可以调用此方法
     */
    public static void clearCache() {
        FILE_CONTENT_CACHE.clear();
        log.debug("Configuration file cache cleared");
    }
    
    /**
     * 清空指定文件的缓存
     * 
     * @param path 文件路径
     */
    public static void clearCache(String path) {
        if (path != null && !path.trim().isEmpty()) {
            FILE_CONTENT_CACHE.remove(path);
            log.debug("Cleared cache for configuration file: {}", path);
        }
    }
    
    /**
     * 获取缓存中的文件数量
     * 
     * @return 缓存文件数量
     */
    public static int getCacheSize() {
        return FILE_CONTENT_CACHE.size();
    }
}