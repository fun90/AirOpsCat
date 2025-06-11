package com.fun90.airopscat.model.dto;

import lombok.Data;

/**
 * SSH配置DTO
 */
@Data
public class SshConfig {
    /**
     * 主机地址
     */
    private String host;

    /**
     * SSH端口
     */
    private int port = 22;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码（密码认证时使用）
     */
    private String password;

    /**
     * 私钥路径（密钥认证时使用）
     */
    private String privateKeyPath;

    /**
     * 私钥内容（密钥认证时使用）
     */
    private String privateKeyContent;

    /**
     * 私钥密码短语
     */
    private String passphrase;

    /**
     * 连接超时时间（毫秒）
     */
    private int timeout = 30000;

    /**
     * 是否使用密钥认证
     */
    public boolean isKeyAuth() {
        return (privateKeyPath != null && !privateKeyPath.trim().isEmpty()) ||
                (privateKeyContent != null && !privateKeyContent.trim().isEmpty());
    }

    /**
     * 是否使用密码认证
     */
    public boolean isPasswordAuth() {
        return password != null && !password.trim().isEmpty();
    }
}