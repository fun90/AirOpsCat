package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.ServerConfig;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SshServiceTest {

    @Test
    public void test() {
        try {
            SshService sshService = new SshService();
            ServerConfig serverConfig = new ServerConfig();
            serverConfig.setHost("154.21.82.153");
//            serverConfig.setHost("154.17.25.61");
            serverConfig.setPort(22);
            serverConfig.setUsername("root");
//            serverConfig.setPassphrase("");
//            serverConfig.setPrivateKeyPath("/Users/omg/env/keys/LightsailDefaultKey-ap-southeast-1.pem");
            serverConfig.setPrivateKeyPath("/Users/omg/env/keys/dmit2.pem");
            Session session = sshService.connectToServer(serverConfig);
            String string = sshService.readRemoteFile(session, "/etc/sing-box/config.json");
            System.out.println(string);
        } catch (IOException | JSchException | SftpException e) {
            throw new RuntimeException(e);
        }
    }

}