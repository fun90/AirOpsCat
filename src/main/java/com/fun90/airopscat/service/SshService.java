package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.BatchCommandResult;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.ServerConfig;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class SshService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SshService.class);
    private static final int DEFAULT_CONNECT_TIMEOUT = 10000;
    private static final int DEFAULT_AUTH_TIMEOUT = 10000;
    private static final long DEFAULT_COMMAND_TIMEOUT = 30000L;

    private final SshClient client;
    private final Map<String, ClientSession> sessionCache = new ConcurrentHashMap<>();

    public SshService() {
        this.client = SshClient.setUpDefaultClient();
        this.client.start();
        // 使用系统默认的known_hosts文件
        // client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        logger.info("SSH client started");
    }

    @Override
    public void close() {
        sessionCache.forEach((key, session) -> {
            try {
                session.close();
                logger.info("Closed session: {}", key);
            } catch (IOException e) {
                logger.warn("Error closing session {}: {}", key, e.getMessage());
            }
        });
        sessionCache.clear();

        client.stop();
        logger.info("SSH client stopped");
    }

    /**
     * 获取或创建SSH会话
     */
    public ClientSession getSession(ServerConfig config) throws IOException {
        String sessionKey = config.getUsername() + "@" + config.getHost() + ":" + config.getPort();
        
        // 检查缓存中是否有可用会话
        ClientSession session = sessionCache.get(sessionKey);
        if (session != null && session.isOpen()) {
            return session;
        }
        
        // 创建新会话
        session = createSession(config);
        sessionCache.put(sessionKey, session);
        return session;
    }

    /**
     * 创建新的SSH会话
     */
    private ClientSession createSession(ServerConfig config) throws IOException {
        try {
            ClientSession session = client.connect(
                config.getUsername(),
                config.getHost(),
                config.getPort()
            )
            .verify(Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT))
            .getSession();

            // 配置认证方式
            if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().isEmpty()) {
                // 使用密钥认证
                session.addPublicKeyIdentity(loadKeyPair(config.getPrivateKeyPath(), config.getPassphrase()));
            } else if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                // 使用密码认证
                session.addPasswordIdentity(config.getPassword());
            } else {
                throw new IllegalArgumentException("Neither password nor private key provided for authentication");
            }

            // 执行认证
            session.auth().verify(Duration.ofMillis(DEFAULT_AUTH_TIMEOUT));
            logger.info("Successfully connected to {}:{}", config.getHost(), config.getPort());
            
            return session;
        } catch (IOException e) {
            logger.error("Failed to connect to {}:{}: {}", config.getHost(), config.getPort(), e.getMessage());
            throw e;
        }
    }

    /**
     * 从文件加载SSH密钥对
     */
    private KeyPair loadKeyPair(String privateKeyPath, String passphrase) throws IOException {
        // 实际应用中需要实现从文件加载密钥对的逻辑
        // 此处为简化示例，实际实现应该使用Apache SSHD的密钥加载工具
        throw new UnsupportedOperationException("Key loading not implemented in this example");
    }

    /**
     * 执行远程命令并返回结果
     */
    public CommandResult executeCommand(ClientSession session, String command, long timeoutMillis) throws IOException {
        CommandResult result = new CommandResult();
        
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ByteArrayOutputStream errorStream = new ByteArrayOutputStream()) {
            
            ChannelExec channel = session.createExecChannel(command);
            channel.setOut(outputStream);
            channel.setErr(errorStream);
            
            channel.open().verify(Duration.ofSeconds(10));
            
            // 等待命令执行完成或超时
            Collection<ClientChannelEvent> events = channel.waitFor(
                EnumSet.of(ClientChannelEvent.CLOSED),
                TimeUnit.MILLISECONDS.toMillis(timeoutMillis)
            );
            
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                result.setExitStatus(-1);
                result.setStderr("Command execution timed out after " + timeoutMillis + "ms");
            } else {
                result.setExitStatus(channel.getExitStatus());
                result.setStdout(outputStream.toString(StandardCharsets.UTF_8));
                result.setStderr(errorStream.toString(StandardCharsets.UTF_8));
            }
            
            channel.close();
            logger.info("Command execution completed with exit status: {}", result.getExitStatus());
        }
        
        return result;
    }
    
    /**
     * 执行命令使用默认超时时间
     */
    public CommandResult executeCommand(ClientSession session, String command) throws IOException {
        return executeCommand(session, command, DEFAULT_COMMAND_TIMEOUT);
    }

    /**
     * 读取远程文件内容
     */
    public String readRemoteFile(ClientSession session, String remotePath) throws IOException {
        SftpClientFactory factory = SftpClientFactory.instance();
        try (SftpClient sftpClient = factory.createSftpClient(session)) {
            try (InputStream is = sftpClient.read(remotePath)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            logger.error("Failed to read remote file {}: {}", remotePath, e.getMessage());
            throw e;
        }
    }

    /**
     * 写入远程文件
     */
    public void writeRemoteFile(ClientSession session, String content, String remotePath) throws IOException {
        SftpClientFactory factory = SftpClientFactory.instance();
        try (SftpClient sftpClient = factory.createSftpClient(session)) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = sftpClient.write(remotePath)) {
                os.write(data);
                os.flush();
            }
            logger.info("Successfully wrote to remote file: {}", remotePath);
        } catch (IOException e) {
            logger.error("Failed to write to remote file {}: {}", remotePath, e.getMessage());
            throw e;
        }
    }

    /**
     * 批量执行命令（使用Java 21虚拟线程）
     */
    public CompletableFuture<BatchCommandResult> batchExecuteCommand(
            List<ServerConfig> serverConfigs, String command) {
        
        BatchCommandResult batchResult = new BatchCommandResult();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (ServerConfig serverConfig : serverConfigs) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    ClientSession session = getSession(serverConfig);
                    CommandResult result = executeCommand(session, command);
                    batchResult.addResult(serverConfig.getHost(), result);
                    // 注意不关闭会话，因为它被缓存供后续使用
                } catch (Exception e) {
                    logger.error("Failed to execute command on {}: {}", 
                            serverConfig.getHost(), e.getMessage());
                    CommandResult errorResult = new CommandResult();
                    errorResult.setExitStatus(-1);
                    errorResult.setStderr("Connection or execution error: " + e.getMessage());
                    batchResult.addResult(serverConfig.getHost(), errorResult);
                }
            });
            
            futures.add(future);
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> batchResult);
    }

    /**
     * 上传本地文件到远程服务器
     */
    public void uploadFile(ClientSession session, Path localPath, String remotePath) throws IOException {
        SftpClientFactory factory = SftpClientFactory.instance();
        try (SftpClient sftpClient = factory.createSftpClient(session)) {
            try (OutputStream os = sftpClient.write(remotePath);
                 InputStream is = Files.newInputStream(localPath)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
            logger.info("Successfully uploaded file to: {}", remotePath);
        } catch (IOException e) {
            logger.error("Failed to upload file to {}: {}", remotePath, e.getMessage());
            throw e;
        }
    }

    /**
     * 下载远程文件到本地
     */
    public void downloadFile(ClientSession session, String remotePath, Path localPath) throws IOException {
        SftpClientFactory factory = SftpClientFactory.instance();
        try (SftpClient sftpClient = factory.createSftpClient(session)) {
            try (InputStream is = sftpClient.read(remotePath);
                 OutputStream os = Files.newOutputStream(localPath)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
            logger.info("Successfully downloaded file from: {}", remotePath);
        } catch (IOException e) {
            logger.error("Failed to download file from {}: {}", remotePath, e.getMessage());
            throw e;
        }
    }
}