package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.SshConfig;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

class SshServiceTest {

    @Test
    public void test() {
        try {
            SshService sshService = new SshService();
            SshConfig sshConfig = new SshConfig();
            sshConfig.setHost("154.21.82.153");
//            sshConfig.setHost("154.17.25.61");
            sshConfig.setPort(22);
            sshConfig.setUsername("root");
//            sshConfig.setPassphrase("");
//            sshConfig.setPrivateKeyPath("/Users/omg/env/keys/LightsailDefaultKey-ap-southeast-1.pem");
            sshConfig.setPrivateKeyPath("/Users/omg/env/keys/dmit2.pem");
            Session session = sshService.connectToServer(sshConfig);
            String string = sshService.readRemoteFile(session, "/etc/sing-box/config.json");
            System.out.println(string);
        } catch (IOException | JSchException | SftpException e) {
            throw new RuntimeException(e);
        }
    }

}