package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.BatchCommandResult;
import com.fun90.airopscat.model.dto.CommandResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SshService {

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    /**
     * 连接到SSH服务器
     */
    public Session connectToServer(SshConfig config) throws JSchException {
        JSch jsch = new JSch();
        
        // 如果使用密钥认证
        if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().isEmpty()) {
            jsch.addIdentity(config.getPrivateKeyPath(), config.getPassphrase());
        }
        
        Session session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
        
        // 如果使用密码认证
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            session.setPassword(config.getPassword());
        }
        
        // 避免提示主机密钥确认
        Properties props = new Properties();
        props.put("StrictHostKeyChecking", "no");
        session.setConfig(props);
        
        session.connect(config.getTimeout());
        log.info("成功连接到服务器: {}:{}", config.getHost(), config.getPort());
        
        return session;
    }
    
    /**
     * 执行命令并返回结果
     */
    public CommandResult executeCommand(Session session, String command) throws JSchException, IOException {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        CommandResult result = new CommandResult();
        
        try {
            channel.setCommand(command);
            
            // 获取标准输出和错误输出
            InputStream inputStream = channel.getInputStream();
            InputStream errorStream = channel.getErrStream();
            
            channel.connect();
            
            // 获取命令输出
            String stdout = new BufferedReader(new InputStreamReader(inputStream))
                    .lines().collect(Collectors.joining("\n"));
            String stderr = new BufferedReader(new InputStreamReader(errorStream))
                    .lines().collect(Collectors.joining("\n"));
            
            result.setExitStatus(channel.getExitStatus());
            result.setStdout(stdout);
            result.setStderr(stderr);
            
            log.info("命令执行完成, 退出码: {}", result.getExitStatus());
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
        
        return result;
    }
    
    /**
     * 读取远程文件
     */
    public String readRemoteFile(Session session, String remotePath) throws JSchException, SftpException, IOException {
        ChannelSftp channelSftp = (ChannelSftp) session.openChannel("sftp");
        StringBuilder content = new StringBuilder();
        
        try {
            channelSftp.connect();
            
            InputStream inputStream = channelSftp.get(remotePath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            
            reader.close();
            log.info("成功读取文件: {}", remotePath);
        } finally {
            if (channelSftp != null) {
                channelSftp.disconnect();
            }
        }
        
        return content.toString();
    }
    
    /**
     * 写入远程文件
     */
    public void writeRemoteFile(Session session, String content, String remotePath) throws JSchException, SftpException, IOException {
        ChannelSftp channelSftp = (ChannelSftp) session.openChannel("sftp");
        
        try {
            channelSftp.connect();
            
            // 创建临时文件
            File tempFile = File.createTempFile("upload", ".tmp");
            FileWriter writer = new FileWriter(tempFile);
            writer.write(content);
            writer.close();
            
            // 上传文件
            channelSftp.put(new FileInputStream(tempFile), remotePath, ChannelSftp.OVERWRITE);
            
            // 删除临时文件
            tempFile.delete();
            
            log.info("成功写入文件: {}", remotePath);
        } finally {
            if (channelSftp != null) {
                channelSftp.disconnect();
            }
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
                try {
                    Session session = connectToServer(sshConfig);
                    CommandResult result = executeCommand(session, command);
                    batchResult.addResult(sshConfig.getHost(), result);
                    session.disconnect();
                } catch (Exception e) {
                    log.error("批量执行命令失败: {}", e.getMessage(), e);
                    CommandResult errorResult = new CommandResult();
                    errorResult.setExitStatus(-1);
                    errorResult.setStderr("连接或执行错误: " + e.getMessage());
                    batchResult.addResult(sshConfig.getHost(), errorResult);
                }
            }
            return batchResult;
        }, executorService);
    }
}