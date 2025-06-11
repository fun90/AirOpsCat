package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.SshConfig;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;

class SshServiceTest {

    @Test
    public void test() {
        try {
            SshService sshService = new SshService();
            SshConfig sshConfig = new SshConfig();
            sshConfig.setTimeout(10000);
            sshConfig.setPort(22);

            sshConfig.setHost("127.0.0.1");
            sshConfig.setUsername("alex");
//            sshConfig.setPassword("Omg1121");
            String privateKeyPath = "/home/alex/.ssh/id_rsa";

            sshConfig.setPrivateKeyPath(privateKeyPath);
//            sshConfig.setPassphrase("");
//            String privateKeyContent = "";
//            sshConfig.setPrivateKeyContent(privateKeyContent);

            ClientSession session = sshService.connectToServer(sshConfig);
            String string = sshService.readRemoteFile(session, "/home/alex/文档/tmp.json");
            System.out.println(string);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}