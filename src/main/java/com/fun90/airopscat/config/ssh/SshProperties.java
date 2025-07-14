package com.fun90.airopscat.config.ssh;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "airopscat.ssh")
public interface SshProperties {
    
    /**
     * SSH提供者类型: apache-sshd, jsch, trilead
     */
    @WithDefault("apache-sshd")
    String provider();
    
    /**
     * 连接池配置
     */
    PoolConfig pool();
    
    interface PoolConfig {
        @WithDefault("false")
        boolean enabled();
        
        @WithDefault("10")
        int maxActive();
        
        @WithDefault("5")
        int maxIdle();
        
        @WithDefault("30000")
        long maxWait();
    }
}