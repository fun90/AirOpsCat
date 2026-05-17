package com.fun90.airopscat.service.ssh;

import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.ServerAuthType;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ServerSshConfigFactory {

    public static final int DEFAULT_PORT = 22;
    public static final String DEFAULT_USERNAME = "root";

    public SshConfig create(Server server) {
        return create(server, null);
    }

    public SshConfig create(Server server, Integer timeout) {
        if (server == null) {
            throw new IllegalArgumentException("服务器不能为空");
        }
        return create(
                server.getIp(),
                server.getSshPort(),
                server.getUsername(),
                server.getAuthType(),
                server.getAuth(),
                timeout
        );
    }

    public SshConfig create(ServerDto server) {
        return create(server, null);
    }

    public SshConfig create(ServerDto server, Integer timeout) {
        if (server == null) {
            throw new IllegalArgumentException("服务器不能为空");
        }
        return create(
                server.getIp(),
                server.getSshPort(),
                server.getUsername(),
                server.getAuthType(),
                server.getAuth(),
                timeout
        );
    }

    private SshConfig create(String host,
                             Integer port,
                             String username,
                             String authType,
                             String auth,
                             Integer timeout) {
        SshConfig config = new SshConfig();
        config.setHost(required(host, "服务器地址不能为空"));
        config.setPort(port == null ? DEFAULT_PORT : port);
        config.setUsername(defaultIfBlank(username, DEFAULT_USERNAME));
        if (timeout != null) {
            config.setTimeout(timeout);
        }

        String authContent = required(auth, "SSH认证信息不能为空");
        if (isPasswordAuth(authType)) {
            config.setPassword(authContent);
        } else if (isKeyAuth(authType)) {
            config.setPrivateKeyContent(normalizePrivateKeyContent(authContent));
        } else {
            throw new IllegalArgumentException("不支持的SSH认证方式: " + authType);
        }
        return config;
    }

    public boolean isPasswordAuth(String authType) {
        return ServerAuthType.PASSWORD.name().equalsIgnoreCase(defaultIfBlank(authType, ServerAuthType.PASSWORD.name()))
                || "password".equalsIgnoreCase(authType);
    }

    public boolean isKeyAuth(String authType) {
        return ServerAuthType.KEY.name().equalsIgnoreCase(authType)
                || "key".equalsIgnoreCase(authType);
    }

    private String normalizePrivateKeyContent(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1).trim();
        }
        validatePrivateKeyContent(normalized);
        return normalized;
    }

    private void validatePrivateKeyContent(String content) {
        if (!content.contains("PRIVATE KEY")) {
            throw new IllegalArgumentException("SSH密钥认证需要填写私钥内容");
        }
        if (content.contains("ENCRYPTED") || content.contains("Proc-Type: 4,ENCRYPTED")) {
            throw new IllegalArgumentException("暂不支持带密码短语的私钥");
        }
    }

    private String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
