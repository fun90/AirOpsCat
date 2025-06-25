package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Server;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务器服务集成测试
 * 验证 JPA 转换器的加密解密功能
 */
@SpringBootTest
@TestPropertySource(properties = {
        "airopscat.crypto.secret-key=TestSecretKey2024ForIntegration",
        "airopscat.rule.url=http://localhost:8080/subscribe/rules"
})
@Transactional
public class ServerServiceIntegrationTest {

    @Autowired
    private ServerService serverService;

    @Test
    void testServerAuthEncryptionDecryption() {
        // 创建服务器对象
        Server server = new Server();
        server.setIp("192.168.1.100");
        server.setUsername("root");
        server.setAuthType("PASSWORD");
        server.setAuth("mySecretPassword123");  // 明文密码
        
        // 保存服务器（应该自动加密）
        Server savedServer = serverService.saveServer(server);
        assertNotNull(savedServer.getId());
        
        // 查询服务器（应该自动解密）
        Server retrievedServer = serverService.getServerById(savedServer.getId());
        assertNotNull(retrievedServer);
        assertEquals("mySecretPassword123", retrievedServer.getAuth());  // 应该是解密后的明文
        
        // 验证 DTO 转换
        var serverDto = serverService.convertToDto(retrievedServer);
        assertEquals("mySecretPassword123", serverDto.getAuth());
    }

    @Test
    void testServerAuthUpdate() {
        // 创建并保存服务器
        Server server = new Server();
        server.setIp("192.168.1.101");
        server.setUsername("admin");
        server.setAuthType("PASSWORD");
        server.setAuth("originalPassword");
        
        Server savedServer = serverService.saveServer(server);
        Long serverId = savedServer.getId();
        
        // 更新密码
        Server updateServer = new Server();
        updateServer.setId(serverId);
        updateServer.setAuth("newPassword123");
        
        Server updatedServer = serverService.updateServer(updateServer);
        
        // 验证更新后的密码
        Server retrievedServer = serverService.getServerById(serverId);
        assertEquals("newPassword123", retrievedServer.getAuth());
    }

    @Test
    void testAuthVerification() {
        // 创建服务器
        Server server = new Server();
        server.setIp("192.168.1.102");
        server.setUsername("test");
        server.setAuthType("PASSWORD");
        server.setAuth("testPassword");
        
        Server savedServer = serverService.saveServer(server);
        
        // 验证认证信息
        assertTrue(serverService.verifyAuth(savedServer.getId(), "testPassword"));
        assertFalse(serverService.verifyAuth(savedServer.getId(), "wrongPassword"));
    }

    @Test
    void testEmptyAuthHandling() {
        // 测试空认证信息
        Server server = new Server();
        server.setIp("192.168.1.103");
        server.setUsername("empty");
        server.setAuthType("PASSWORD");
        server.setAuth(null);
        
        Server savedServer = serverService.saveServer(server);
        
        Server retrievedServer = serverService.getServerById(savedServer.getId());
        assertNull(retrievedServer.getAuth());
        
        // 测试空字符串
        server.setAuth("");
        Server updatedServer = serverService.updateServer(server);
        
        retrievedServer = serverService.getServerById(updatedServer.getId());
        assertEquals("", retrievedServer.getAuth());
    }

    @Test
    void testPrivateKeyAuth() {
        // 测试私钥认证
        String privateKey = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAo...\n-----END PRIVATE KEY-----";
        
        Server server = new Server();
        server.setIp("192.168.1.104");
        server.setUsername("keyuser");
        server.setAuthType("KEY");
        server.setAuth(privateKey);
        
        Server savedServer = serverService.saveServer(server);
        
        Server retrievedServer = serverService.getServerById(savedServer.getId());
        assertEquals(privateKey, retrievedServer.getAuth());
    }
} 