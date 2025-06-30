package com.fun90.airopscat.config.ssh;

import com.fun90.airopscat.service.ssh.provider.JschConnectionProvider;
import com.fun90.airopscat.service.ssh.provider.SshConnectionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SshProperties.class)
public class SshAutoConfiguration {
    
    @Bean
    @ConditionalOnProperty(name = "airopscat.ssh.provider", havingValue = "jsch")
    public SshConnectionProvider jschProvider() {
        return new JschConnectionProvider();
    }

    // 可以轻松添加新的实现
//    @Bean
//    @ConditionalOnProperty(name = "airopscat.ssh.provider", havingValue = "jsch")
//    public SshConnectionProvider jschProvider() {
//        return new JschConnectionProvider();
//    }
}