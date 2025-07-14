package com.fun90.airopscat.service.ssh;

import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.service.ssh.provider.SshConnectionProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
@ApplicationScoped
public class SshConnectionService {
    
    @Inject
    SshConnectionProvider connectionProvider;
    
    /**
     * 创建SSH连接 - API保持简单
     */
    public SshConnection createConnection(SshConfig config) throws IOException {
        log.debug("创建SSH连接: {}:{}", config.getHost(), config.getPort());
        return connectionProvider.createConnection(config);
    }
    
    /**
     * 测试SSH连接
     */
    public boolean testConnection(SshConfig config) {
        try (SshConnection connection = createConnection(config)) {
            return connection.isConnected();
        } catch (Exception e) {
            log.warn("SSH连接测试失败: {}", e.getMessage());
            return false;
        }
    }
}