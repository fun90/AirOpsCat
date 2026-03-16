package com.fun90.airopscat.config;

/**
 * 应用常量配置
 */
public final class AppConstants {
    
    private AppConstants() {
        // 工具类不允许实例化
    }
    
    /**
     * 应用版本号
     */
    public static final String APP_VERSION = "2.1.4";
    
    /**
     * GitHub API 基础 URL
     */
    public static final String GITHUB_API_BASE_URL = "https://api.github.com";
    
    /**
     * GitHub 仓库路径
     */
    public static final String GITHUB_REPO_PATH = "/repos/fun90/AirOpsCat/releases/latest";
    
    /**
     * GitHub API 完整 URL
     */
    public static final String GITHUB_API_URL = GITHUB_API_BASE_URL + GITHUB_REPO_PATH;
    
    /**
     * HTTP 连接超时时间 (毫秒)
     */
    public static final int HTTP_CONNECT_TIMEOUT = 5000;
    
    /**
     * HTTP 读取超时时间 (毫秒)
     */
    public static final int HTTP_READ_TIMEOUT = 10000;
    
    /**
     * 更新检查超时时间 (秒)
     */
    public static final int UPDATE_CHECK_TIMEOUT_SECONDS = 10;
    
    /**
     * 生产环境是否启用更新检查
     */
    public static final boolean UPDATE_CHECK_ENABLED = true;
}