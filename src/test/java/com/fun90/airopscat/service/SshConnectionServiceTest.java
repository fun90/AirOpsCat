//package com.fun90.airopscat.service;
//
//import com.fun90.airopscat.model.dto.SshConfig;
//import com.fun90.airopscat.service.ssh.SshConnection;
//import com.fun90.airopscat.service.ssh.SshConnectionService;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//
///**
// * SSH连接服务测试 - 解耦版本
// */
//@SpringBootTest
//class SshConnectionServiceTest {
//
//    @Autowired
//    private SshConnectionService sshConnectionService;
//
//    @Test
//    public void testSshConnection() {
//        try {
//            SshConfig sshConfig = new SshConfig();
//            sshConfig.setTimeout(10000);
//            sshConfig.setPort(22);
//            sshConfig.setHost("127.0.0.1");
//            sshConfig.setUsername("alex");
//            sshConfig.setPrivateKeyPath("/home/alex/.ssh/id_rsa");
//
//            // 使用try-with-resources自动管理连接生命周期
//            try (SshConnection connection = sshConnectionService.createConnection(sshConfig)) {
//                // 测试文件读取
//                String fileContent = connection.readRemoteFile("/home/alex/文档/tmp.json");
//                System.out.println("文件内容: " + fileContent);
//
//                // 测试命令执行
//                var commandResult = connection.executeCommand("ls -la");
//                System.out.println("命令输出: " + commandResult.getStdout());
//
//                // 测试连接状态
//                System.out.println("连接状态: " + connection.isConnected());
//                System.out.println("连接信息: " + connection.getConnectionInfo());
//            }
//
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    @Test
//    public void testConnectionTest() {
//        SshConfig sshConfig = new SshConfig();
//        sshConfig.setTimeout(10000);
//        sshConfig.setPort(22);
//        sshConfig.setHost("127.0.0.1");
//        sshConfig.setUsername("alex");
//        sshConfig.setPrivateKeyPath("/home/alex/.ssh/id_rsa");
//
//        boolean isConnected = sshConnectionService.testConnection(sshConfig);
//        System.out.println("连接测试结果: " + isConnected);
//    }
//}