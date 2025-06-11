package com.fun90.airopscat.service.ssh;

import com.fun90.airopscat.model.dto.CommandResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SSH连接抽象接口，隐藏底层SSH实现细节
 */
public interface SshConnection extends AutoCloseable {
    
    /**
     * 执行命令
     * @param command 要执行的命令
     * @return 命令执行结果
     * @throws IOException 执行异常
     */
    CommandResult executeCommand(String command) throws IOException;
    
    /**
     * 读取远程文件内容
     * @param remotePath 远程文件路径
     * @return 文件内容
     * @throws IOException 读取异常
     */
    String readRemoteFile(String remotePath) throws IOException;
    
    /**
     * 写入远程文件
     * @param remotePath 远程文件路径
     * @param content 文件内容
     * @throws IOException 写入异常
     */
    void writeRemoteFile(String remotePath, String content) throws IOException;
    
    /**
     * 上传文件
     * @param localPath 本地文件路径
     * @param remotePath 远程文件路径
     * @throws IOException 上传异常
     */
    void uploadFile(String localPath, String remotePath) throws IOException;
    
    /**
     * 下载文件
     * @param remotePath 远程文件路径
     * @param localPath 本地文件路径
     * @throws IOException 下载异常
     */
    void downloadFile(String remotePath, String localPath) throws IOException;
    
    /**
     * 创建SFTP输入流
     * @param remotePath 远程文件路径
     * @return 输入流
     * @throws IOException 创建异常
     */
    InputStream createInputStream(String remotePath) throws IOException;
    
    /**
     * 创建SFTP输出流
     * @param remotePath 远程文件路径
     * @return 输出流
     * @throws IOException 创建异常
     */
    OutputStream createOutputStream(String remotePath) throws IOException;
    
    /**
     * 检查连接是否有效
     * @return 连接状态
     */
    boolean isConnected();
    
    /**
     * 获取连接信息（用于日志和调试）
     * @return 连接信息字符串
     */
    String getConnectionInfo();
}
