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
     * 连接到SSH服务器，支持密码和密钥两种认证方式
     *
     * @param config SSH配置信息
     * @return Session对象
     * @throws JSchException 连接异常
     */
    public Session connectToServer(SshConfig config) throws JSchException {
        JSch jsch = new JSch();
        Session session = null;

        try {
            log.info("开始连接SSH服务器: {}:{}", config.getHost(), config.getPort());

            // 创建会话
            session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());

            // 设置连接属性
            Properties props = new Properties();
            props.put("StrictHostKeyChecking", "no");
            props.put("UserKnownHostsFile", "/dev/null");
            // 可选：设置加密算法优先级
            props.put("PreferredAuthentications", "publickey,keyboard-interactive,password");
            session.setConfig(props);

            // 设置连接超时
            if (config.getTimeout() > 0) {
                session.setTimeout(config.getTimeout());
            } else {
                session.setTimeout(30000); // 默认30秒超时
            }

            // 判断认证方式并设置认证信息
            boolean authConfigured = false;

            // 1. 优先使用密钥认证
            if (hasPrivateKeyAuth(config)) {
                try {
                    if (config.getPrivateKeyContent() != null && !config.getPrivateKeyContent().trim().isEmpty()) {
                        // 使用私钥字符串内容
                        byte[] privateKey = config.getPrivateKeyContent().getBytes();
                        byte[] passphrase = null;
                        if (config.getPassphrase() != null && !config.getPassphrase().isEmpty()) {
                            passphrase = config.getPassphrase().getBytes();
                        }

                        // 生成一个唯一的标识名
                        String keyName = "private_key_" + System.currentTimeMillis();
                        jsch.addIdentity(keyName, privateKey, null, passphrase);
                        log.info("使用私钥字符串认证{}", passphrase != null ? " (带密码短语)" : "");

                    } else if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().trim().isEmpty()) {
                        // 使用私钥文件路径
                        if (config.getPassphrase() != null && !config.getPassphrase().isEmpty()) {
                            // 带密码短语的私钥文件
                            jsch.addIdentity(config.getPrivateKeyPath(), config.getPassphrase());
                            log.info("使用带密码短语的私钥文件认证: {}", config.getPrivateKeyPath());
                        } else {
                            // 无密码短语的私钥文件
                            jsch.addIdentity(config.getPrivateKeyPath());
                            log.info("使用私钥文件认证: {}", config.getPrivateKeyPath());
                        }
                    }
                    authConfigured = true;
                } catch (Exception e) {
                    log.warn("私钥认证配置失败，将尝试其他认证方式: {}", e.getMessage(), e);
                }
            }

            // 2. 如果私钥认证未配置或失败，使用密码认证
            if (!authConfigured && config.getPassword() != null && !config.getPassword().trim().isEmpty()) {
                session.setPassword(config.getPassword());
                log.info("使用密码认证");
                authConfigured = true;
            }

            // 3. 检查是否有认证方式
            if (!authConfigured) {
                throw new JSchException("未配置有效的认证方式，请提供私钥内容/路径或密码");
            }

            // 建立连接
            log.info("正在连接到服务器...");
            session.connect();

            // 验证连接状态
            if (!session.isConnected()) {
                throw new JSchException("连接建立失败");
            }

            log.info("成功连接到SSH服务器: {}:{}, 用户: {}",
                    config.getHost(), config.getPort(), config.getUsername());

            return session;

        } catch (JSchException e) {
            // 连接失败时清理资源
            if (session != null && session.isConnected()) {
                session.disconnect();
            }

            log.error("SSH连接失败 {}:{} - {}", config.getHost(), config.getPort(), e.getMessage());

            // 提供更友好的错误信息
            String errorMessage = getConnectErrorMessage(e);
            throw new JSchException(errorMessage);
        }
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
    public void disconnectSafely(Session session) {
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
                log.info("SSH连接已断开");
            } catch (Exception e) {
                log.warn("断开SSH连接时发生异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取连接错误的友好提示信息
     *
     * @param e JSch异常
     * @return 友好的错误信息
     */
    private String getConnectErrorMessage(JSchException e) {
        String message = e.getMessage().toLowerCase();

        if (message.contains("auth fail") || message.contains("authentication fail")) {
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