package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.core.CoreManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ServerConfigServiceTest {

    @Autowired
    private ServerConfigService serverConfigService;

    @Autowired
    private ServerConfigRepository serverConfigRepository;

    @Autowired
    private ServerRepository serverRepository;

    @Test
    void testGetServerConfigPage() {
        // 测试分页查询
        Page<ServerConfig> page = serverConfigService.getServerConfigPage(1, 10, null, null);
        assertNotNull(page);
        assertNotNull(page.getContent());
    }

    @Test
    void testGetConfigTypeOptions() {
        // 测试获取配置类型选项
        var options = serverConfigService.getConfigTypeOptions();
        assertNotNull(options);
    }

    @Test
    void testGetServerConfigStats() {
        // 测试获取统计信息
        var stats = serverConfigService.getServerConfigStats();
        assertNotNull(stats);
        assertTrue(stats.containsKey("total"));
    }
} 