package com.fun90.airopscat.config.ssh;

import com.fun90.airopscat.service.ssh.provider.JschConnectionProvider;
import com.fun90.airopscat.service.ssh.provider.SshConnectionProvider;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class SshAutoConfiguration {
    
    @Produces
    @DefaultBean
    public SshConnectionProvider jschProvider() {
        return new JschConnectionProvider();
    }

    // 可以轻松添加新的实现
    // @Produces
    // @ConditionalOnProperty(name = "airopscat.ssh.provider", havingValue = "jsch")
    // public SshConnectionProvider jschProvider() {
    //     return new JschConnectionProvider();
    // }
}