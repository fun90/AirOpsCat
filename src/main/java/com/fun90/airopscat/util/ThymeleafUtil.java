package com.fun90.airopscat.util;

import io.quarkus.qute.Engine;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class ThymeleafUtil {
    
    @Inject
    Engine engine;
    
    /**
     * 使用Qute处理字符串模板
     * Note: Quarkus uses Qute instead of Thymeleaf for templating
     */
    public String processStringTemplate(String templateContent, Map<String, Object> variables) {
        try {
            // 创建模板实例
            TemplateInstance instance = engine.parse(templateContent).instance();
            
            // 添加变量
            if (variables != null) {
                variables.forEach(instance::data);
            }
            
            // 渲染模板
            return instance.render();
        } catch (Exception e) {
            throw new RuntimeException("Failed to process string template", e);
        }
    }
    
    /**
     * 使用Qute处理模板文件
     * Note: In Quarkus, templates are typically loaded from src/main/resources/templates
     */
    public String processTemplate(String templateName, Map<String, Object> variables) {
        try {
            // 获取模板
            TemplateInstance instance = engine.getTemplate(templateName).instance();
            
            // 添加变量
            if (variables != null) {
                variables.forEach(instance::data);
            }
            
            return instance.render();
        } catch (Exception e) {
            throw new RuntimeException("Failed to process template: " + templateName, e);
        }
    }
}