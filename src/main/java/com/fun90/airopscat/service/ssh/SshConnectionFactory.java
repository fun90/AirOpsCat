package com.fun90.airopscat.service.ssh;

import com.fun90.airopscat.model.dto.SshConfig;
import java.io.IOException;

/**
 * SSH连接工厂接口
 */
public interface SshConnectionFactory {
    
    /**
     * 创建SSH连接
     * @param config SSH配置
     * @return SSH连接实例
     * @throws IOException 连接异常
     */
    SshConnection createConnection(SshConfig config) throws IOException;
    
    /**
     * 获取工厂类型
     * @return 工厂类型名称
     */
    String getFactoryType();
    
    /**
     * 关闭工厂，释放资源
     */
    void shutdown();
}