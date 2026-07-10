package com.fun90.airopscat.service.ssh.impl;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.config.SshSmokeTestMode;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshLocalPortForward;
import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSch SSH连接实现 - 简化版
 * 完全兼容 GraalVM Native Image
 * 
 * 简化原则：
 * 1. 懒加载连接 - 只在需要时建立连接
 * 2. 统一资源管理 - 简化Session和SFTP通道管理
 * 3. 减少状态检查 - 移除不必要的连接状态检查
 * 4. 集中异常处理 - 统一异常转换逻辑
 */
@Slf4j
public class JschConnection implements SshConnection {

    private static final Class<? extends Signature> ED25519_SIGNATURE_CLASS =
            com.jcraft.jsch.bc.SignatureEd25519.class;
    private static final Class<? extends Signature> ED448_SIGNATURE_CLASS =
            com.jcraft.jsch.bc.SignatureEd448.class;
    private static final Class<? extends KeyPairGenEdDSA> EDDSA_KEY_PAIR_GENERATOR_CLASS =
            com.jcraft.jsch.bc.KeyPairGenEdDSA.class;

    private static final String PUBLIC_KEY_ALGORITHMS = String.join(",",
            "ssh-ed25519",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "rsa-sha2-512",
            "rsa-sha2-256",
            "ssh-rsa");

    private static final String SERVER_HOST_KEY_ALGORITHMS = String.join(",",
            "ssh-ed25519",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "rsa-sha2-512",
            "rsa-sha2-256",
            "ssh-rsa");
    
    private final SshConfig config;
    private final JSch jsch;
    private Session session;
    private ChannelSftp sftpChannel;
    
    public JschConnection(SshConfig config) {
        this.config = config;
        configureEdDsaProvider();
        this.jsch = new JSch();
        this.jsch.setInstanceLogger(new SanitizedJschLogger());
    }

    private static void configureEdDsaProvider() {
        JSch.setConfig("ssh-ed25519", ED25519_SIGNATURE_CLASS.getName());
        JSch.setConfig("ssh-ed448", ED448_SIGNATURE_CLASS.getName());
        JSch.setConfig("keypairgen.eddsa", EDDSA_KEY_PAIR_GENERATOR_CLASS.getName());
        if (SshSmokeTestMode.isEnabled()) {
            log.info("JSch EdDSA配置: ssh-ed25519={}, ssh-ed448={}, keypairgen.eddsa={}",
                    JSch.getConfig("ssh-ed25519"),
                    JSch.getConfig("ssh-ed448"),
                    JSch.getConfig("keypairgen.eddsa"));
            checkSignatureAvailability("ssh-ed25519");
            checkSignatureAvailability("ssh-ed448");
            checkKeyPairGeneratorAvailability();
        }
    }

    private static void checkSignatureAvailability(String algorithm) {
        String className = JSch.getConfig(algorithm);
        try {
            Class<?> signatureClass = Class.forName(className);
            Constructor<?> constructor = signatureClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Signature signature = (Signature) constructor.newInstance();
            signature.init();
            log.info("JSch签名算法可用: algorithm={}, class={}", algorithm, className);
        } catch (Throwable e) {
            log.error("JSch签名算法不可用: algorithm={}, class={}", algorithm, className, e);
        }
    }

    private static void checkKeyPairGeneratorAvailability() {
        String className = JSch.getConfig("keypairgen.eddsa");
        try {
            Class<?> keyPairGeneratorClass = Class.forName(className);
            Constructor<?> constructor = keyPairGeneratorClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            KeyPairGenEdDSA keyPairGenerator = (KeyPairGenEdDSA) constructor.newInstance();
            keyPairGenerator.init("Ed25519", 256);
            log.info("JSch EdDSA密钥生成器可用: class={}, pubKeyLength={}",
                    className,
                    keyPairGenerator.getPub() == null ? 0 : keyPairGenerator.getPub().length);
        } catch (Throwable e) {
            log.error("JSch EdDSA密钥生成器不可用: class={}", className, e);
        }
    }
    
    @Override
    public CommandResult executeCommand(String command) throws IOException {
        ensureConnected();
        
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
            
            channel.setOutputStream(outputStream);
            channel.setErrStream(errorStream);
            channel.connect(config.getTimeout());
            
            // 等待命令执行完成
            while (!channel.isClosed()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("命令执行被中断: " + command, e);
                }
            }
            
            CommandResult result = new CommandResult();
            result.setExitStatus(channel.getExitStatus());
            result.setStdout(outputStream.toString(StandardCharsets.UTF_8));
            result.setStderr(errorStream.toString(StandardCharsets.UTF_8));
            return result;
                    
        } catch (JSchException e) {
            throw new IOException("执行命令失败: " + command, e);
        } finally {
            closeQuietly(channel);
        }
    }
    
    @Override
    public String readRemoteFile(String remotePath) throws IOException {
        try (InputStream inputStream = getRemoteFileInputStream(remotePath);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[8192]; // 增大缓冲区提高性能
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            return outputStream.toString(StandardCharsets.UTF_8);
        }
    }
    
    @Override
    public void writeRemoteFile(String remotePath, String content) throws IOException {
        try (OutputStream outputStream = getRemoteFileOutputStream(remotePath)) {
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }
    }

    @Override
    public InputStream getRemoteFileInputStream(String remotePath) throws IOException {
        ensureSftpChannelConnected();
        
        try {
            return sftpChannel.get(remotePath);
        } catch (SftpException e) {
            throw new IOException("获取远程文件输入流失败: " + remotePath, e);
        }
    }
    
    @Override
    public OutputStream getRemoteFileOutputStream(String remotePath) throws IOException {
        ensureSftpChannelConnected();
        
        try {
            return sftpChannel.put(remotePath);
        } catch (SftpException e) {
            throw new IOException("获取远程文件输出流失败: " + remotePath, e);
        }
    }
    
    @Override
    public String getConnectionInfo() {
        return String.format("SSH连接 [%s@%s:%d]", 
                config.getUsername(), config.getHost(), config.getPort());
    }

    @Override
    public int forwardLocalPort(int localPort, String remoteHost, int remotePort) throws IOException {
        ensureConnected();
        try {
            return session.setPortForwardingL(localPort, remoteHost, remotePort);
        } catch (JSchException e) {
            throw new IOException(String.format("建立本地端口转发失败: %d -> %s:%d",
                    localPort, remoteHost, remotePort), e);
        }
    }

    @Override
    public SshLocalPortForward openLocalPortForward(int preferredLocalPort, String remoteHost, int remotePort) throws IOException {
        ensureConnected();
        Session currentSession = session;
        int actualLocalPort = forwardLocalPort(preferredLocalPort, remoteHost, remotePort);
        AtomicBoolean closed = new AtomicBoolean(false);
        return new SshLocalPortForward() {
            @Override
            public int localPort() {
                return actualLocalPort;
            }

            @Override
            public String remoteHost() {
                return remoteHost;
            }

            @Override
            public int remotePort() {
                return remotePort;
            }

            @Override
            public void close() throws IOException {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                if (currentSession == null || !currentSession.isConnected()) {
                    return;
                }
                try {
                    currentSession.delPortForwardingL(actualLocalPort);
                } catch (JSchException e) {
                    throw new IOException("关闭本地端口转发失败: " + actualLocalPort, e);
                }
            }
        };
    }

    @Override
    public void cancelLocalPortForward(int localPort) throws IOException {
        ensureConnected();
        try {
            session.delPortForwardingL(localPort);
        } catch (JSchException e) {
            throw new IOException("取消本地端口转发失败: " + localPort, e);
        }
    }
    
    @Override
    public void close() {
        closeQuietly(sftpChannel);
        closeQuietly(session);
        sftpChannel = null;
        session = null;
    }
    
    /**
     * 确保Session连接已建立
     */
    private void ensureConnected() throws IOException {
        if (session == null || !session.isConnected()) {
            connectSession();
        }
    }
    
    /**
     * 确保SFTP通道已连接
     */
    private void ensureSftpChannelConnected() throws IOException {
        ensureConnected();
        
        if (sftpChannel == null || !sftpChannel.isConnected()) {
            connectSftpChannel();
        }
    }
    
    /**
     * 建立Session连接
     */
    private void connectSession() throws IOException {
        try {
            configureAuthentication();
            
            session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
            
            if (config.getPassword() != null && !config.getPassword().trim().isEmpty()) {
                session.setPassword(config.getPassword());
            }
            
            // 配置会话属性
            Properties properties = new Properties();
            properties.put("StrictHostKeyChecking", "no");
            properties.put("PreferredAuthentications", "publickey,password");
            properties.put("PubkeyAcceptedAlgorithms", PUBLIC_KEY_ALGORITHMS);
            properties.put("PubkeyAcceptedKeyTypes", PUBLIC_KEY_ALGORITHMS);
            properties.put("server_host_key", SERVER_HOST_KEY_ALGORITHMS);
            session.setConfig(properties);
            session.setTimeout(config.getTimeout());
            
            session.connect();
            log.debug("SSH会话连接成功: {}", getConnectionInfo());
            
        } catch (JSchException e) {
            throw new IOException("SSH连接失败: " + getConnectionInfo(), e);
        }
    }
    
    /**
     * 建立SFTP通道连接
     */
    private void connectSftpChannel() throws IOException {
        try {
            sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();
            log.debug("SFTP通道连接成功: {}", getConnectionInfo());
        } catch (JSchException e) {
            throw new IOException("SFTP通道连接失败: " + getConnectionInfo(), e);
        }
    }
    
    /**
     * 配置认证方式
     */
    private void configureAuthentication() throws JSchException {
        if (config.getPrivateKeyContent() != null && !config.getPrivateKeyContent().trim().isEmpty()) {
            // 从字符串内容加载私钥
            byte[] passphraseBytes = config.getPassphrase() != null ? 
                    config.getPassphrase().getBytes(StandardCharsets.UTF_8) : null;
            jsch.addIdentity("key", 
                    config.getPrivateKeyContent().getBytes(StandardCharsets.UTF_8),
                    null, passphraseBytes);
            log.debug("SSH密钥认证已配置: source=content, passphrase={}", passphraseBytes != null);
        } else if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().trim().isEmpty()) {
            // 从文件加载私钥
            if (config.getPassphrase() != null && !config.getPassphrase().trim().isEmpty()) {
                jsch.addIdentity(config.getPrivateKeyPath(), config.getPassphrase());
            } else {
                jsch.addIdentity(config.getPrivateKeyPath());
            }
            log.debug("SSH密钥认证已配置: source=path, passphrase={}",
                    config.getPassphrase() != null && !config.getPassphrase().trim().isEmpty());
        } else if (config.getPassword() != null && !config.getPassword().trim().isEmpty()) {
            log.debug("SSH密码认证已配置");
        }
    }

    private static class SanitizedJschLogger implements Logger {

        @Override
        public boolean isEnabled(int level) {
            if (SshSmokeTestMode.isEnabled()) {
                return true;
            }
            return level >= Logger.WARN ? log.isWarnEnabled() : log.isDebugEnabled();
        }

        @Override
        public void log(int level, String message) {
            String safeMessage = message == null ? "" : message
                    .replaceAll("(?i)(password|passphrase)=[^,\\s]+", "$1=<hidden>");
            if (level >= Logger.ERROR) {
                log.warn("JSch: {}", safeMessage);
            } else if (level >= Logger.WARN) {
                log.warn("JSch: {}", safeMessage);
            } else if (SshSmokeTestMode.isEnabled()) {
                log.info("JSch: {}", safeMessage);
            } else {
                log.debug("JSch: {}", safeMessage);
            }
        }
    }
    
    /**
     * 安静地关闭资源
     */
    private void closeQuietly(Object resource) {
        if (resource == null) return;
        
        try {
            if (resource instanceof Channel) {
                ((Channel) resource).disconnect();
            } else if (resource instanceof Session) {
                ((Session) resource).disconnect();
            }
        } catch (Exception e) {
            log.debug("关闭SSH资源时发生异常: {}", e.getMessage());
        }
    }
} 
