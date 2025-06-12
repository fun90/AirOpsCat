package com.fun90.airopscat.service.ssh;

import com.fun90.airopscat.model.dto.SshConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * SSH服务
 */
@Slf4j
@Service
public class SshConnectionService {
    
    private final SshConnectionFactory connectionFactory;
    
    @Autowired
    public SshConnectionService(SshConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }
    
    /**
     * 创建SSH连接
     * @param config SSH配置
     * @return SSH连接实例
     * @throws IOException 连接异常
     */
    public SshConnection createConnection(SshConfig config) throws IOException {
        log.debug("使用工厂 [{}] 创建SSH连接", connectionFactory.getFactoryType());
        return connectionFactory.createConnection(config);
    }
    
    /**
     * 测试SSH连接
     * @param config SSH配置
     * @return 连接是否成功
     */
    public boolean testConnection(SshConfig config) {
        try (SshConnection connection = createConnection(config)) {
            return connection.isConnected();
        } catch (Exception e) {
            log.warn("SSH连接测试失败: {}", e.getMessage());
            return false;
        }
    }
    
    @PreDestroy
    public void shutdown() {
        connectionFactory.shutdown();
    }
}