package com.fun90.airopscat.service.core;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class CoreManagementServiceTest {

    @Autowired
    private CoreManagementService coreManagementService;

    @Test
    void testInstallXray() throws IOException {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost("127.0.0.1");
        sshConfig.setPort(22);
        sshConfig.setUsername("root");
        sshConfig.setPrivateKeyPath("/home/alex/.ssh/id_rsa");

        // 安装
        CoreManagementResult result = coreManagementService.executeOperation("xray", CoreOperation.INSTALL, sshConfig);
        System.out.println(JsonUtil.toJsonString(result));
        assertTrue(result.isSuccess());

    }

    @Test
    void testExecuteOperation() throws IOException {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost("127.0.0.1");
        sshConfig.setPort(22);
        sshConfig.setUsername("root");
        sshConfig.setPrivateKeyPath("/home/alex/.ssh/id_rsa");

        // 安装
        coreManagementService.executeOperation("xray", CoreOperation.INSTALL, sshConfig);

        // 上传配置到服务器
        String configContent = Files.readString(Path.of("/home/alex/文档/tmp.json"));
        CoreManagementResult result = coreManagementService.executeOperation("xray", CoreOperation.CONFIG, sshConfig, configContent);
        System.out.println(JsonUtil.toJsonString(result));
        assertTrue(result.isSuccess());
        // 重启Xray
        result = coreManagementService.executeOperation("xray", CoreOperation.RESTART, sshConfig);
        System.out.println(JsonUtil.toJsonString(result));
        assertTrue(result.isSuccess());
    }

}