package com.fun90.airopscat.model.enums;

import lombok.Getter;

/**
 * 内核管理操作类型
 */
@Getter
public enum CoreOperation {
    START("start", "启动"),
    STOP("stop", "停止"),
    RESTART("restart", "重启"),
    RELOAD("reload", "重新加载"),
    STATUS("status", "状态检查"),
    VALIDATE_CONFIG("validate", "配置验证"),
    INSTALL("install", "安装"),
    UNINSTALL("uninstall", "卸载"),
    UPDATE("update", "更新"),
    CONFIG("config", "配置"),
    GET_VERSION("version", "获取版本"),
    GET_LOGS("logs", "获取日志"),
    IS_INSTALLED("is_installed", "检查安装状态");

    private final String code;
    private final String description;

    CoreOperation(String code, String description) {
        this.code = code;
        this.description = description;
    }

}
