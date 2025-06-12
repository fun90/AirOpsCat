package com.fun90.airopscat.service.ssh.provider;

import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.impl.ApacheSshdConnection;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ApacheSshdConnectionProvider implements SshConnectionProvider {
    
    private volatile SshClient sshClient;

    @Override
    public SshConnection createConnection(SshConfig config) throws IOException {
        SshClient client = getOrCreateClient();
        ClientSession session = null;

        try {
            log.info("开始创建SSH连接: {}:{}", config.getHost(), config.getPort());

            // 创建连接
            ConnectFuture connectFuture = client.connect(config.getUsername(), config.getHost(), config.getPort());
            int timeout = config.getTimeout() > 0 ? config.getTimeout() : 10000;
            session = connectFuture.verify(timeout, TimeUnit.MILLISECONDS).getSession();

            // 认证
            boolean authSuccess = authenticate(session, config);
            if (!authSuccess) {
                if (session != null) {
                    session.close();
                }
                throw new IOException("认证失败：请检查用户名、密码或私钥是否正确");
            }

            log.info("SSH连接创建成功: {}:{}, 用户: {}",
                    config.getHost(), config.getPort(), config.getUsername());

            return new ApacheSshdConnection(session);

        } catch (Exception e) {
            // 连接失败时清理资源
            if (session != null) {
                try {
                    session.close();
                } catch (IOException closeException) {
                    log.warn("关闭会话时发生异常: {}", closeException.getMessage());
                }
            }

            log.error("SSH连接创建失败 {}:{} - {}", config.getHost(), config.getPort(), e.getMessage());
            throw new IOException(getConnectErrorMessage(e), e);
        }
    }

    private SshClient getOrCreateClient() {
        if (sshClient == null) {
            synchronized (this) {
                if (sshClient == null) {
                    sshClient = SshClient.setUpDefaultClient();

                    // 配置客户端超时设置
                    sshClient.getProperties().put(
                            org.apache.sshd.core.CoreModuleProperties.IDLE_TIMEOUT.getName(), 60000L);
                    sshClient.getProperties().put(
                            org.apache.sshd.core.CoreModuleProperties.NIO2_READ_TIMEOUT.getName(), 30000L);

                    sshClient.start();
                    log.info("SSH客户端已启动");
                }
            }
        }
        return sshClient;
    }

    private boolean authenticate(ClientSession session, SshConfig config) throws IOException {
        try {
            AuthFuture authFuture;

            if (hasPrivateKey(config)) {
                // 使用私钥认证
                authFuture = authenticateWithPrivateKey(session, config);
            } else if (config.getPassword() != null && !config.getPassword().trim().isEmpty()) {
                // 使用密码认证
                authFuture = session.auth();
                session.addPasswordIdentity(config.getPassword());
            } else {
                throw new IOException("缺失认证信息：请提供密码或私钥");
            }

            // 等待认证完成
            int timeout = config.getTimeout() > 0 ? config.getTimeout() : 10000;
            return authFuture.verify(timeout, TimeUnit.MILLISECONDS).isSuccess();

        } catch (Exception e) {
            log.error("SSH认证失败: {}", e.getMessage());
            return false;
        }
    }

    private AuthFuture authenticateWithPrivateKey(ClientSession session, SshConfig config) throws IOException {
        if (config.getPrivateKeyContent() != null && !config.getPrivateKeyContent().trim().isEmpty()) {
            // 使用私钥字符串内容
            Path tempKeyFile = Files.createTempFile("ssh_key", ".pem");
            try {
                Files.write(tempKeyFile, config.getPrivateKeyContent().getBytes(StandardCharsets.UTF_8));
                FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(tempKeyFile);

                if (config.getPassphrase() != null && !config.getPassphrase().isEmpty()) {
                    keyPairProvider.setPasswordFinder((session1, resourceKey, retryIndex) -> config.getPassphrase());
                }

                session.setKeyIdentityProvider(keyPairProvider);
            } finally {
                // 清理临时文件
                try {
                    Files.deleteIfExists(tempKeyFile);
                } catch (Exception e) {
                    log.warn("删除临时私钥文件失败: {}", e.getMessage());
                }
            }
        } else if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().trim().isEmpty()) {
            // 使用私钥文件路径
            Path keyPath = Path.of(config.getPrivateKeyPath());
            FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(keyPath);

            if (config.getPassphrase() != null && !config.getPassphrase().isEmpty()) {
                keyPairProvider.setPasswordFinder((session1, resourceKey, retryIndex) -> config.getPassphrase());
            }

            session.setKeyIdentityProvider(keyPairProvider);
        }

        return session.auth();
    }

    private boolean hasPrivateKey(SshConfig config) {
        return (config.getPrivateKeyContent() != null && !config.getPrivateKeyContent().trim().isEmpty()) ||
                (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().trim().isEmpty());
    }

    private String getConnectErrorMessage(Exception e) {
        String message = e.getMessage().toLowerCase();

        if (message.contains("auth") && (message.contains("fail") || message.contains("denied"))) {
            return "认证失败：请检查用户名、密码或私钥是否正确";
        } else if (message.contains("connection refused")) {
            return "连接被拒绝：请检查服务器地址、端口是否正确，以及SSH服务是否运行";
        } else if (message.contains("timeout") || message.contains("timed out")) {
            return "连接超时：请检查网络连接和服务器状态";
        } else if (message.contains("unknown host")) {
            return "主机不存在：请检查服务器地址是否正确";
        } else if (message.contains("permission denied")) {
            return "权限被拒绝：请检查用户权限和认证信息";
        } else if (message.contains("key") && message.contains("invalid")) {
            return "私钥无效：请检查私钥文件格式和密码短语";
        } else {
            return "SSH连接失败：" + e.getMessage();
        }
    }
}