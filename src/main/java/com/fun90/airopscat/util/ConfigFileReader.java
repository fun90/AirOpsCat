package com.fun90.airopscat.util;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.Config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置文件读取工具类
 * 支持从 classpath 读取配置文件并转换为对象或JSON字符串
 */
@Slf4j
@ApplicationScoped
public class ConfigFileReader {

    @Inject
    Config config;

    // 缓存已读取的配置文件内容，避免重复读取，使用线程安全的ConcurrentHashMap
    private final Map<String, String> fileContentCache = new ConcurrentHashMap<>();

    /**
     * 从 classpath 或外部目录读取配置文件内容
     * 优先从外部目录读取，如果不存在则从 classpath 读取
     *
     * @param path 文件路径，相对于 classpath 根目录或外部目录
     * @return 文件内容字符串
     * @throws RuntimeException 如果文件读取失败
     */
    public String readFileContent(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }

        // 检查缓存
//        if (fileContentCache.containsKey(path)) {
//            log.debug("Reading file content from cache: {}", path);
//            return fileContentCache.get(path);
//        }

        String content = null;

        // 1. 首先尝试从外部目录读取
        content = readFromExternalDirectory(path);

        // 2. 如果外部目录没有找到，尝试从 classpath 读取
        if (content == null) {
            content = readFromClasspath(path);
        }

        if (content == null) {
            throw new IllegalArgumentException("Configuration file not found: " + path);
        }

        // 缓存文件内容
//        fileContentCache.put(path, content);
        log.debug("Successfully read configuration file: {}", path);

        return content;
    }

    /**
     * 从外部目录读取文件内容
     *
     * @param path 文件路径
     * @return 文件内容，如果文件不存在返回 null
     */
    private String readFromExternalDirectory(String path) {
        try {
            // 获取外部模板目录配置
            String templatesDir = config
                .getOptionalValue("airopscat.config.templates.dir", String.class)
                .orElse("./config");

            // 构建完整的外部文件路径
            Path externalFilePath;
            if (path.startsWith("config/")) {
                // 如果路径以 config/ 开头，去掉这个前缀
                String relativePath = path.substring("config/".length());
                externalFilePath = Paths.get(templatesDir, relativePath);
            } else {
                externalFilePath = Paths.get(templatesDir, path);
            }

            if (Files.exists(externalFilePath) && Files.isRegularFile(externalFilePath)) {
                log.debug("Reading file from external directory: {}", externalFilePath);
                return Files.readString(externalFilePath, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Failed to read file from external directory: {}", path, e);
        }

        return null;
    }

    /**
     * 从 classpath 读取文件内容
     *
     * @param path 文件路径
     * @return 文件内容，如果文件不存在返回 null
     */
    private String readFromClasspath(String path) {
        try {
            // 确保路径不以 classpath: 开头（因为我们直接使用 getResourceAsStream）
            String resourcePath = path.startsWith("classpath:") ? path.substring(10) : path;

            // 使用 ClassLoader 读取资源
            InputStream inputStream = ConfigFileReader.class.getClassLoader().getResourceAsStream(resourcePath);

            if (inputStream != null) {
                try (InputStream is = inputStream) {
                    log.debug("Reading file from classpath: {}", resourcePath);
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read file from classpath: {}", path, e);
        }

        return null;
    }


}
