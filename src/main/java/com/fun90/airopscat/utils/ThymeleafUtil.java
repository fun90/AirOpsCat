package com.fun90.airopscat.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.util.Map;

@Component
public class ThymeleafUtil {
    
    private final TemplateEngine templateEngine;
    
    @Autowired
    public ThymeleafUtil(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }
    
    /**
     * 使用Thymeleaf处理字符串模板
     */
    public String processStringTemplate(String templateContent, Map<String, Object> variables) {
        // 创建字符串模板解析器
        StringTemplateResolver templateResolver = new StringTemplateResolver();
        templateResolver.setTemplateMode(TemplateMode.TEXT);
        templateResolver.setCacheable(false);
        
        // 创建临时模板引擎
        TemplateEngine stringTemplateEngine = new TemplateEngine();
        stringTemplateEngine.setTemplateResolver(templateResolver);
        
        // 创建上下文并添加变量
        Context context = new Context();
        if (variables != null) {
            variables.forEach(context::setVariable);
        }
        
        // 处理模板
        return stringTemplateEngine.process(templateContent, context);
    }
    
    /**
     * 使用Thymeleaf处理模板文件
     */
    public String processTemplate(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        if (variables != null) {
            variables.forEach(context::setVariable);
        }
        return templateEngine.process(templateName, context);
    }
} 