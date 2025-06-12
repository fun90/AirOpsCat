package com.fun90.airopscat.service.ssh.impl;

import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.service.ssh.SshConnection;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Apache SSHD实现的SSH连接
 */
@Slf4j
public class ApacheSshdConnection implements SshConnection {
    
    private final ClientSession session;
    private final String connectionInfo;
    private volatile boolean closed = false;
    
    public ApacheSshdConnection(ClientSession session) {
        this.session = session;
        SocketAddress remoteAddress = session.getRemoteAddress();
        int port = (remoteAddress instanceof InetSocketAddress) ? 
            ((InetSocketAddress) remoteAddress).getPort() : 22;
        
        this.connectionInfo = String.format("%s@%s:%d", 
            session.getUsername(), 
            session.getRemoteAddress(), 
            port);
    }
    
    @Override
    public CommandResult executeCommand(String command) throws IOException {
        if (closed || !session.isOpen()) {
            throw new IOException("SSH连接已关闭");
        }
        
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
            
            Integer exitStatus = channel.getExitStatus();
            result.setExitStatus(exitStatus != null ? exitStatus : -1);
            result.setStdout(stdout.toString(StandardCharsets.UTF_8));
            result.setStderr(stderr.toString(StandardCharsets.UTF_8));
            
            log.debug("命令执行完成: {} (退出码: {})", command, result.getExitStatus());
            
        } catch (Exception e) {
            log.error("命令执行失败: {} - {}", command, e.getMessage());
            result.setStderr("命令执行异常: " + e.getMessage());
            result.setExitStatus(-1);
        }
        
        return result;
    }
    
    @Override
    public String readRemoteFile(String remotePath) throws IOException {
        if (closed || !session.isOpen()) {
            throw new IOException("SSH连接已关闭");
        }
        
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            try (InputStream inputStream = sftpClient.read(remotePath);
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                
                return outputStream.toString(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("读取远程文件失败: {} - {}", remotePath, e.getMessage());
            throw new IOException("读取远程文件失败: " + remotePath, e);
        }
    }
    
    @Override
    public void writeRemoteFile(String remotePath, String content) throws IOException {
        if (closed || !session.isOpen()) {
            throw new IOException("SSH连接已关闭");
        }
        
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            try (OutputStream outputStream = sftpClient.write(remotePath)) {
                outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.error("写入远程文件失败: {} - {}", remotePath, e.getMessage());
            throw new IOException("写入远程文件失败: " + remotePath, e);
        }
    }
    
    @Override
    public void uploadFile(String localPath, String remotePath) throws IOException {
        if (closed || !session.isOpen()) {
            throw new IOException("SSH连接已关闭");
        }
        
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            try (InputStream inputStream = Files.newInputStream(Paths.get(localPath));
                 OutputStream outputStream = sftpClient.write(remotePath)) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            log.error("上传文件失败: {} -> {} - {}", localPath, remotePath, e.getMessage());
            throw new IOException("上传文件失败: " + localPath + " -> " + remotePath, e);
        }
    }
    
    @Override
    public void downloadFile(String remotePath, String localPath) throws IOException {
        if (closed || !session.isOpen()) {
            throw new IOException("SSH连接已关闭");
        }
        
        try (SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session)) {
            try (InputStream inputStream = sftpClient.read(remotePath);
                 OutputStream outputStream = Files.newOutputStream(Paths.get(localPath))) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            log.error("下载文件失败: {} -> {} - {}", remotePath, localPath, e.getMessage());
            throw new IOException("下载文件失败: " + remotePath + " -> " + localPath, e);
        }
    }
    
    @Override
    public InputStream createInputStream(String remotePath) throws IOException {
        if (closed || !session.isOpen()) {
            throw new IOException("SSH连接已关闭");
        }
        
        try {
            SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session);
            return sftpClient.read(remotePath);
        } catch (Exception e) {
            log.error("创建输入流失败: {} - {}", remotePath, e.getMessage());
            throw new IOException("创建输入流失败: " + remotePath, e);
        }
    }
    
    @Override
    public OutputStream createOutputStream(String remotePath) throws IOException {
        if (closed || !session.isOpen()) {
            throw new IOException("SSH连接已关闭");
        }
        
        try {
            SftpClient sftpClient = SftpClientFactory.instance().createSftpClient(session);
            return sftpClient.write(remotePath);
        } catch (Exception e) {
            log.error("创建输出流失败: {} - {}", remotePath, e.getMessage());
            throw new IOException("创建输出流失败: " + remotePath, e);
        }
    }
    
    @Override
    public boolean isConnected() {
        return !closed && session != null && session.isOpen();
    }
    
    @Override
    public String getConnectionInfo() {
        return connectionInfo;
    }
    
    @Override
    public void close() throws IOException {
        if (!closed && session != null) {
            closed = true;
            try {
                session.close();
                log.debug("SSH连接已关闭: {}", connectionInfo);
            } catch (Exception e) {
                log.warn("关闭SSH连接时发生异常: {} - {}", connectionInfo, e.getMessage());
                throw new IOException("关闭SSH连接失败", e);
            }
        }
    }
}