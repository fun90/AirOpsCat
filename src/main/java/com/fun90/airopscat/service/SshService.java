package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.BatchCommandResult;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.SshConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SshService {

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final SshClientManager clientManager = new SshClientManager();

    /**
     * SSH客户端管理器，负责创建和管理SSH客户端实例
     */
    private static class SshClientManager {
        private volatile SshClient sshClient;

        public SshClient getClient() {
            if (sshClient == null) {
                synchronized (this) {
                    if (sshClient == null) {
                        sshClient = SshClient.setUpDefaultClient();
                        
                        // 配置客户端超时设置
                        sshClient.getProperties().put(org.apache.sshd.core.CoreModuleProperties.IDLE_TIMEOUT.getName(), 60000L);
                        sshClient.getProperties().put(org.apache.sshd.core.CoreModuleProperties.NIO2_READ_TIMEOUT.getName(), 30000L);
                        
                        sshClient.start();
                        log.info("SSH客户端已启动");
                    }
                }
            }
            return sshClient;
        }

        public void shutdown() {
            if (sshClient != null) {
                sshClient.stop();
                log.info("SSH客户端已关闭");
            }
        }
    }

    /**
     * 连接到SSH服务器，支持密码和密钥两种认证方式
     *
     * @param config SSH配置信息
     * @return ClientSession对象
     * @throws IOException 连接异常
     */
    public ClientSession connectToServer(SshConfig config) throws IOException {
        SshClient client = clientManager.getClient();
        ClientSession session = null;

        try {
            log.info("开始连接SSH服务器: {}:{}", config.getHost(), config.getPort());

            // 创建连接
            ConnectFuture connectFuture = client.connect(config.getUsername(), config.getHost(), config.getPort());
            
            // 设置连接超时
            int timeout = config.getTimeout() > 0 ? config.getTimeout() : 30000;
            session = connectFuture.verify(timeout, TimeUnit.MILLISECONDS).getSession();

            // 注意：Apache MINA SSHD的会话超时通过客户端配置或在连接时设置

            // 判断认证方式并进行认证
            boolean authSuccess = false;

            // 1. 优先使用密钥认证
            if (hasPrivateKeyAuth(config)) {
                try {
                    authSuccess = authenticateWithPrivateKey(session, config);
                    if (authSuccess) {
                        log.info("私钥认证成功");
                    }
                } catch (Exception e) {
                    log.warn("私钥认证失败，将尝试其他认证方式: {}", e.getMessage());
                }
            }

            // 2. 如果私钥认证未成功，使用密码认证
            if (!authSuccess && config.getPassword() != null && !config.getPassword().trim().isEmpty()) {
                try {
                    // 设置密码身份提供者
                    session.setPasswordIdentityProvider(
                        org.apache.sshd.client.auth.password.PasswordIdentityProvider.wrapPasswords(config.getPassword())
                    );
                    AuthFuture authFuture = session.auth();
                    authSuccess = authFuture.verify(timeout, TimeUnit.MILLISECONDS).isSuccess();
                    if (authSuccess) {
                        log.info("密码认证成功");
                    }
                } catch (Exception e) {
                    log.warn("密码认证失败: {}", e.getMessage());
                }
            }

            // 3. 检查认证结果
            if (!authSuccess) {
                if (session != null) {
                    session.close();
                }
                throw new IOException("认证失败：请检查用户名、密码或私钥是否正确");
            }

            log.info("成功连接到SSH服务器: {}:{}, 用户: {}",
                    config.getHost(), config.getPort(), config.getUsername());

            return session;

        } catch (Exception e) {
            // 连接失败时清理资源
            if (session != null) {
                try {
                    session.close();
                } catch (IOException closeException) {
                    log.warn("关闭会话时发生异常: {}", closeException.getMessage());
                }
            }

            log.error("SSH连接失败 {}:{} - {}", config.getHost(), config.getPort(), e.getMessage());

            // 提供更友好的错误信息
            String errorMessage = getConnectErrorMessage(e);
            throw new IOException(errorMessage, e);
        }
    }

    /**
     * 使用私钥进行认证
     */
    private boolean authenticateWithPrivateKey(ClientSession session, SshConfig config) throws IOException {
        try {
            if (config.getPrivateKeyContent() != null && !config.getPrivateKeyContent().trim().isEmpty()) {
                // 使用私钥字符串内容
                log.info("使用私钥字符串认证");
                
                // 将私钥内容写入临时文件
                Path tempKeyFile = Files.createTempFile("ssh_key", ".pem");
                try {
                    Files.write(tempKeyFile, config.getPrivateKeyContent().getBytes(StandardCharsets.UTF_8));
                    
                    // 设置密钥身份提供者
                    FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(tempKeyFile);
                    
                    // 如果有密码短语，设置密码提供者
                    if (config.getPassphrase() != null && !config.getPassphrase().isEmpty()) {
                        keyPairProvider.setPasswordFinder((session1, resourceKey, retryIndex) -> config.getPassphrase());
                    }
                    
                    session.setKeyIdentityProvider(keyPairProvider);
                    
                    AuthFuture authFuture = session.auth();
                    return authFuture.verify(30000, TimeUnit.MILLISECONDS).isSuccess();
                } finally {
                    Files.deleteIfExists(tempKeyFile);
                }

            } else if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().trim().isEmpty()) {
                // 使用私钥文件路径
                log.info("使用私钥文件认证: {}", config.getPrivateKeyPath());
                Path keyPath = Paths.get(config.getPrivateKeyPath());
                
                if (!Files.exists(keyPath)) {
                    throw new IOException("私钥文件不存在: " + config.getPrivateKeyPath());
                }

                // 设置密钥身份提供者
                FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(keyPath);
                
                // 如果有密码短语，设置密码提供者
                if (config.getPassphrase() != null && !config.getPassphrase().isEmpty()) {
                    keyPairProvider.setPasswordFinder((session1, resourceKey, retryIndex) -> config.getPassphrase());
                }
                
                session.setKeyIdentityProvider(keyPairProvider);
                
                AuthFuture authFuture = session.auth();
                return authFuture.verify(30000, TimeUnit.MILLISECONDS).isSuccess();
            }
        } catch (Exception e) {
            log.error("私钥认证过程中发生异常: {}", e.getMessage(), e);
            throw new IOException("私钥认证失败", e);
        }
        
        return false;
    }

    /**
     * 检查是否配置了私钥认证
     */
    private boolean hasPrivateKeyAuth(SshConfig config) {
        return (config.getPrivateKeyContent() != null && !config.getPrivateKeyContent().trim().isEmpty()) ||
                (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().trim().isEmpty());
    }

    /**
     * 安全地断开SSH连接
     *
     * @param session 要断开的会话
     */
    public void disconnectSafely(ClientSession session) {
        if (session != null && !session.isClosed()) {
            try {
                session.close();
                log.info("SSH连接已断开");
            } catch (Exception e) {
                log.warn("断开SSH连接时发生异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取连接错误的友好提示信息
     *
     * @param e 异常
     * @return 友好的错误信息
     */
    private String getConnectErrorMessage(Exception e) {
        String message = e.getMessage().toLowerCase();

        if (message.contains("auth") && (message.contains("fail") || message.contains("denied"))) {
            return "认证失败：请检查用户名、密码或私钥是否正确";
        } else if (message.contains("connection refused")) {
            return "连接被拒绝：请检查服务器地址、端口是否正确，以及SSH服务是否运行";
        } else if (message.contains("timeout") || message.contains("timed out")) {
            return "连接超时：请检查网络连接和服务器状态";
        } else if (message.contains("unknown host") || message.contains("name or service not known")) {
            return "主机不存在：请检查服务器地址是否正确";
        } else if (message.contains("permission denied")) {
            return "权限被拒绝：请检查用户权限和认证信息";
        } else if (message.contains("key") && message.contains("invalid")) {
            return "私钥无效：请检查私钥文件格式和密码短语";
        } else {
            return "SSH连接失败：" + e.getMessage();
        }
    }

    /**
     * 执行命令并返回结果
     */
    public CommandResult executeCommand(ClientSession session, String command) throws IOException {
        CommandResult result = new CommandResult();
        
        try (ChannelExec channel = session.createExecChannel(command)) {
            // 设置输出流
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            
            channel.setOut(stdout);
            channel.setErr(stderr);
            
            // 执行命令
            channel.open().verify(30000, TimeUnit.MILLISECONDS);
            channel.waitFor(java.util.EnumSet.of(
                org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), 0);
            
            // 获取结果
            result.setExitStatus(channel.getExitStatus());
            result.setStdout(stdout.toString(StandardCharsets.UTF_8));
            result.setStderr(stderr.toString(StandardCharsets.UTF_8));
            
            log.info("命令执行完成, 退出码: {}", result.getExitStatus());
        }
        
        return result;
    }

    /**
     * 读取远程文件
     */
    public String readRemoteFile(ClientSession session, String remotePath) throws IOException {
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            
            try (InputStream inputStream = sftpClient.read(remotePath);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                
                log.info("成功读取文件: {}", remotePath);
                return content.toString();
            }
        }
    }

    /**
     * 写入远程文件
     */
    public void writeRemoteFile(ClientSession session, String content, String remotePath) throws IOException {
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            
            // 直接写入内容到远程文件
            try (OutputStream outputStream = sftpClient.write(remotePath, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)) {
                outputStream.write(content.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
            
            log.info("成功写入文件: {}", remotePath);
        }
    }

    /**
     * 批量执行命令
     */
    public CompletableFuture<BatchCommandResult> batchExecuteCommand(
            List<SshConfig> sshConfigs, String command) {
        
        BatchCommandResult batchResult = new BatchCommandResult();
        
        return CompletableFuture.supplyAsync(() -> {
            for (SshConfig sshConfig : sshConfigs) {
                ClientSession session = null;
                try {
                    session = connectToServer(sshConfig);
                    CommandResult result = executeCommand(session, command);
                    batchResult.addResult(sshConfig.getHost(), result);
                } catch (Exception e) {
                    log.error("批量执行命令失败: {}", e.getMessage(), e);
                    CommandResult errorResult = new CommandResult();
                    errorResult.setExitStatus(-1);
                    errorResult.setStderr("连接或执行错误: " + e.getMessage());
                    batchResult.addResult(sshConfig.getHost(), errorResult);
                } finally {
                    if (session != null) {
                        disconnectSafely(session);
                    }
                }
            }
            return batchResult;
        }, executorService);
    }

    /**
     * 关闭服务时清理资源
     */
    public void shutdown() {
        try {
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdown();
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        clientManager.shutdown();
    }
}