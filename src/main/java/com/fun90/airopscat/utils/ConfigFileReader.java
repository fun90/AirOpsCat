package com.fun90.airopscat.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ConfigFileReader {

    @Autowired
    private ResourceLoader resourceLoader;

    /**
     * 读取classpath下配置文件内容
     *
     * @param filePath 相对于classpath的路径，如 "config/xray-template.json"
     * @return 文件内容
     */
    public String readClasspathFile(String filePath) throws IOException {
        Resource resource = resourceLoader.getResource("classpath:" + filePath);
        if (!resource.exists()) {
            throw new IOException("配置文件不存在: " + filePath);
        }

        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
    }

    /**
     * 读取多个配置文件
     *
     * @param filePaths 文件路径列表
     * @return 文件内容映射
     */
    public java.util.Map<String, String> readMultipleFiles(List<String> filePaths) {
        return filePaths.stream().collect(Collectors.toMap(path -> path, path -> {
            try {
                return readClasspathFile(path);
            } catch (IOException e) {
                return "读取失败: " + e.getMessage();
            }
        }));
    }

    /**
     * 读取JSON配置文件并解析为对象
     *
     * @param filePath JSON文件路径
     * @param clazz    目标类型
     * @return 解析后的对象
     */
    public <T> T readJsonConfig(String filePath, Class<T> clazz) throws IOException {
        String content = readClasspathFile(filePath);
        return JsonUtil.toObject(content, clazz);
    }

    /**
     * 检查配置文件是否存在
     *
     * @param filePath 文件路径
     * @return 是否存在
     */
    public boolean fileExists(String filePath) {
        Resource resource = resourceLoader.getResource("classpath:" + filePath);
        return resource.exists();
    }
}