package com.fun90.airopscat.service.ssh;

import com.fun90.airopscat.model.dto.CommandResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SSH连接抽象接口，简化的SSH操作API
 * 隐藏连接管理复杂性，提供易用的操作接口
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
     * 获取远程文件输入流（用于大文件读取）
     * @param remotePath 远程文件路径
     * @return 输入流，使用完毕后需要关闭
     * @throws IOException 创建异常
     */
    InputStream getRemoteFileInputStream(String remotePath) throws IOException;
    
    /**
     * 获取远程文件输出流（用于大文件写入）
     * @param remotePath 远程文件路径
     * @return 输出流，使用完毕后需要关闭
     * @throws IOException 创建异常
     */
    OutputStream getRemoteFileOutputStream(String remotePath) throws IOException;
    
    /**
     * 获取连接信息（用于日志和调试）
     * @return 连接信息字符串
     */
    String getConnectionInfo();

    /**
     * 建立本地端口转发
     * @param localPort 本地端口，传0时由系统自动分配
     * @param remoteHost 远端主机
     * @param remotePort 远端端口
     * @return 实际绑定的本地端口
     * @throws IOException 建立失败
     */
    int forwardLocalPort(int localPort, String remoteHost, int remotePort) throws IOException;

    /**
     * 取消本地端口转发
     * @param localPort 本地端口
     * @throws IOException 取消失败
     */
    void cancelLocalPortForward(int localPort) throws IOException;
}
