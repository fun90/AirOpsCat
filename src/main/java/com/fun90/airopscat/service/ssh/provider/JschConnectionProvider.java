package com.fun90.airopscat.service.ssh.provider;

import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.impl.JschConnection;

import java.io.IOException;

/**
 * JSch SSH连接提供者
 * 完全兼容 GraalVM Native Image
 */
public class JschConnectionProvider implements SshConnectionProvider {
    
    @Override
    public SshConnection createConnection(SshConfig config) throws IOException {
        return new JschConnection(config);
    }
} 