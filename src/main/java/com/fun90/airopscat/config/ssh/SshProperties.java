package com.fun90.airopscat.config.ssh;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "airopscat.ssh")
@Data
public class SshProperties {
    /**
     * SSH提供者类型: apache-sshd, jsch, trilead
     */
    private String provider = "apache-sshd";
    
    /**
     * 连接池配置
     */
    private PoolConfig pool = new PoolConfig();
    
    @Data
    public static class PoolConfig {
        private boolean enabled = false;
        private int maxActive = 10;
        private int maxIdle = 5;
        private long maxWait = 30000;
    }
}