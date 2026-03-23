package com.fun90.airopscat.config;

/**
 * 应用常量配置
 */
public final class AppConstants {

    private AppConstants() {
        // 工具类不允许实例化
    }

    /**
     * 更新检查超时时间 (秒)
     */
    public static final int UPDATE_CHECK_TIMEOUT_SECONDS = 10;

    /**
     * 生产环境是否启用更新检查
     */
    public static final boolean UPDATE_CHECK_ENABLED = true;
}
