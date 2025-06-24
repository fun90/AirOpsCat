package com.fun90.airopscat.service;

import com.fun90.airopscat.utils.ThymeleafUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SubscriptionServiceTest {

    @Autowired
    private ThymeleafUtil thymeleafUtil;

    @Test
    public void testThymeleafTemplateProcessing() {
        // 测试简单的Thymeleaf模板处理
        String template = """
                Hello [[${name}]]!
                Your age is [[${age}]].
                [# th:if="${isActive}"]
                You are active.
                [/]
                """;

        Map<String, Object> variables = new HashMap<>();
        variables.put("name", "John");
        variables.put("age", 25);
        variables.put("isActive", true);

        String result = thymeleafUtil.processStringTemplate(template, variables);

        assertNotNull(result);
        assertTrue(result.contains("Hello John!"));
        assertTrue(result.contains("Your age is 25."));
        assertTrue(result.contains("You are active."));
    }

    @Test
    public void testThymeleafListProcessing() {
        // 测试列表处理
        String template = """
                Proxies:
                [# th:each="proxy : ${proxies}"]
                - [(${proxy.name})]: [(${proxy.server})]:[(${proxy.port})]
                [/]
                """;

        Map<String, Object> variables = new HashMap<>();
        Map<String, Object> proxy1 = new HashMap<>();
        proxy1.put("name", "Server1");
        proxy1.put("server", "192.168.1.1");
        proxy1.put("port", 8080);

        Map<String, Object> proxy2 = new HashMap<>();
        proxy2.put("name", "Server2");
        proxy2.put("server", "192.168.1.2");
        proxy2.put("port", 8081);

        variables.put("proxies", java.util.Arrays.asList(proxy1, proxy2));

        String result = thymeleafUtil.processStringTemplate(template, variables);

        assertNotNull(result);
        assertTrue(result.contains("Server1"));
        assertTrue(result.contains("Server2"));
        assertTrue(result.contains("192.168.1.1:8080"));
        assertTrue(result.contains("192.168.1.2:8081"));
    }
} 