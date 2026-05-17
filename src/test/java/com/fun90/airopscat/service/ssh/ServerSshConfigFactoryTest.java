package com.fun90.airopscat.service.ssh;

import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerSshConfigFactoryTest {

    private final ServerSshConfigFactory factory = new ServerSshConfigFactory();

    @Test
    void createPasswordConfigFromServer() {
        Server server = new Server();
        server.setIp("192.0.2.10");
        server.setSshPort(2222);
        server.setUsername("admin");
        server.setAuthType("PASSWORD");
        server.setAuth("secret");

        SshConfig config = factory.create(server, 5000);

        assertEquals("192.0.2.10", config.getHost());
        assertEquals(2222, config.getPort());
        assertEquals("admin", config.getUsername());
        assertEquals("secret", config.getPassword());
        assertNull(config.getPrivateKeyContent());
        assertEquals(5000, config.getTimeout());
    }

    @Test
    void createKeyConfigFromServerDtoWithDefaults() {
        ServerDto server = new ServerDto();
        server.setIp("192.0.2.11");
        server.setAuthType("KEY");
        server.setAuth("\r\n-----BEGIN OPENSSH PRIVATE KEY-----\r\nkey\r\n-----END OPENSSH PRIVATE KEY-----\r\n");

        SshConfig config = factory.create(server);

        assertEquals("192.0.2.11", config.getHost());
        assertEquals(22, config.getPort());
        assertEquals("root", config.getUsername());
        assertNull(config.getPassword());
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\nkey\n-----END OPENSSH PRIVATE KEY-----", config.getPrivateKeyContent());
        assertNotNull(config.getTimeout());
    }

    @Test
    void rejectEmptyAuthContent() {
        Server server = new Server();
        server.setIp("192.0.2.12");
        server.setAuthType("PASSWORD");
        server.setAuth(" ");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> factory.create(server));

        assertEquals("SSH认证信息不能为空", error.getMessage());
    }

    @Test
    void rejectUnsupportedAuthType() {
        Server server = new Server();
        server.setIp("192.0.2.13");
        server.setAuthType("TOKEN");
        server.setAuth("secret");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> factory.create(server));

        assertEquals("不支持的SSH认证方式: TOKEN", error.getMessage());
    }

    @Test
    void rejectEncryptedPrivateKeyContent() {
        Server server = new Server();
        server.setIp("192.0.2.14");
        server.setAuthType("KEY");
        server.setAuth("-----BEGIN ENCRYPTED PRIVATE KEY-----\nkey\n-----END ENCRYPTED PRIVATE KEY-----");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> factory.create(server));

        assertEquals("暂不支持带密码短语的私钥", error.getMessage());
    }
}
