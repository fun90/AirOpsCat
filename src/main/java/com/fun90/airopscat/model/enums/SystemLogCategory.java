package com.fun90.airopscat.model.enums;

/**
 * 系统日志分类。新增日志来源时在此加一项，页面标签和筛选自动出现。
 */
public enum SystemLogCategory {
    SERVER_FAILOVER("服务器切换");

    private final String label;

    SystemLogCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
