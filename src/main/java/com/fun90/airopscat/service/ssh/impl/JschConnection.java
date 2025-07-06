package com.fun90.airopscat.service.ssh.impl;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * JSch SSH连接实现
 * 完全兼容 GraalVM Native Image
 */
@Slf4j
public class JschConnection implements SshConnection {
    
    private final SshConfig config;
    private JSch jsch;
    private Session session;
    private ChannelSftp sftpChannel;
    
    public JschConnection(SshConfig config) {
        this.config = config;
        this.jsch = new JSch();
    }
    
    @Override
    public CommandResult executeCommand(String command) throws IOException {
        if (!isConnected()) {
            connect();
        }
        
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
                    break;
                }
            }
            
            int exitCode = channel.getExitStatus();
            String output = outputStream.toString(StandardCharsets.UTF_8);
            String error = errorStream.toString(StandardCharsets.UTF_8);
            
            CommandResult result = new CommandResult();
            result.setExitStatus(exitCode);
            result.setStdout(output);
            result.setStderr(error);
            
            return result;
                    
        } catch (JSchException e) {
            throw new IOException("执行命令失败: " + command, e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }
    
    @Override
    public String readRemoteFile(String remotePath) throws IOException {
        if (!isConnected()) {
            connect();
        }
        
        try (InputStream inputStream = createInputStream(remotePath);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            return outputStream.toString(StandardCharsets.UTF_8);
        }
    }
    
    @Override
    public void writeRemoteFile(String remotePath, String content) throws IOException {
        if (!isConnected()) {
            connect();
        }
        
        try (OutputStream outputStream = createOutputStream(remotePath)) {
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }
    }
    
    @Override
    public void uploadFile(String localPath, String remotePath) throws IOException {
        if (!isConnected()) {
            connect();
        }
        
        try {
            sftpChannel.put(localPath, remotePath);
        } catch (SftpException e) {
            throw new IOException("上传文件失败: " + localPath + " -> " + remotePath, e);
        }
    }
    
    @Override
    public void downloadFile(String remotePath, String localPath) throws IOException {
        if (!isConnected()) {
            connect();
        }
        
        try {
            sftpChannel.get(remotePath, localPath);
        } catch (SftpException e) {
            throw new IOException("下载文件失败: " + remotePath + " -> " + localPath, e);
        }
    }
    
    @Override
    public InputStream createInputStream(String remotePath) throws IOException {
        if (!isConnected()) {
            connect();
        }
        
        try {
            return sftpChannel.get(remotePath);
        } catch (SftpException e) {
            throw new IOException("创建输入流失败: " + remotePath, e);
        }
    }
    
    @Override
    public OutputStream createOutputStream(String remotePath) throws IOException {
        if (!isConnected()) {
            connect();
        }
        
        try {
            return sftpChannel.put(remotePath);
        } catch (SftpException e) {
            throw new IOException("创建输出流失败: " + remotePath, e);
        }
    }
    
    @Override
    public boolean isConnected() {
        return session != null && session.isConnected();
    }
    
    @Override
    public String getConnectionInfo() {
        return String.format("SSH连接 [%s@%s:%d]", 
                config.getUsername(), config.getHost(), config.getPort());
    }
    
    @Override
    public void close() throws Exception {
        if (sftpChannel != null) {
            sftpChannel.disconnect();
            sftpChannel = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }
    
    private void connect() throws IOException {
        try {
            // 设置密钥认证
            if (StringUtils.hasText(config.getPrivateKeyContent())) {
                // 从字符串内容加载私钥
                jsch.addIdentity("key", 
                        config.getPrivateKeyContent().getBytes(StandardCharsets.UTF_8),
                        null,
                        config.getPassphrase() != null ? 
                                config.getPassphrase().getBytes(StandardCharsets.UTF_8) : null);
            } else if (StringUtils.hasText(config.getPrivateKeyPath())) {
                // 从文件加载私钥
                if (StringUtils.hasText(config.getPassphrase())) {
                    jsch.addIdentity(config.getPrivateKeyPath(), config.getPassphrase());
                } else {
                    jsch.addIdentity(config.getPrivateKeyPath());
                }
            }
            
            // 创建会话
            session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
            
            // 设置密码认证
            if (StringUtils.hasText(config.getPassword())) {
                session.setPassword(config.getPassword());
            }
            
            // 配置会话
            Properties properties = new Properties();
            properties.put("StrictHostKeyChecking", "no");
            properties.put("PreferredAuthentications", "publickey,password");
            session.setConfig(properties);
            
            // 设置超时
            session.setTimeout(config.getTimeout());
            
            // 连接
            session.connect();
            
            // 创建SFTP通道
            sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();
            
            log.info("SSH连接成功: {}", getConnectionInfo());
            
        } catch (JSchException e) {
            throw new IOException("SSH连接失败: " + getConnectionInfo(), e);
        }
    }
} 