package com.fun90.airopscat.model.dto;

import lombok.Data;

/**
 * SSH配置
 */
@Data
public class SshConfig {
    private String host;
    private int port = 22;
    private String username;
    private String password;
    private String privateKeyPath;
    private String passphrase;
    private int timeout = 30000;
}
