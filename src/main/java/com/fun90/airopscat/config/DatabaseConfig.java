package com.fun90.airopscat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

/**
 * 数据库配置类
 * 配置事务管理和超时策略
 */
@Configuration
@EnableTransactionManagement
public class DatabaseConfig {
    
    /**
     * 配置事务模板，设置合适的超时时间
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // 设置事务超时时间为30秒
        template.setTimeout(5);
        return template;
    }
    
    /**
     * 配置JPA事务管理器 (移除可能与SQLite冲突的配置)
     */
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory);
        // 移除默认超时设置，让Spring使用默认配置
        // transactionManager.setDefaultTimeout(30);
        return transactionManager;
    }
} 