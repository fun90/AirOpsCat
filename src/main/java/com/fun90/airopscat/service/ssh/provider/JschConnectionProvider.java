package com.fun90.airopscat.service.ssh.provider;

import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.impl.JschConnection;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * JSch SSH连接提供者
 * 完全兼容 GraalVM Native Image
 */
@ApplicationScoped
public class JschConnectionProvider implements SshConnectionProvider {
    
    @Override
    public SshConnection createConnection(SshConfig config) {
        return new JschConnection(config);
    }
} 