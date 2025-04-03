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
            serverConfig.setHost("154.17.25.61");
            serverConfig.setPort(22);
            serverConfig.setUsername("root");
            serverConfig.setPassphrase("");
            serverConfig.setPrivateKeyPath("-----BEGIN RSA PRIVATE KEY-----\n" +
                    "MIIEpAIBAAKCAQEAw+HE1ZtrmOCyD2eAGIK8AK2UuWQr4jvfGZ15Meqi/ef4FOTD\n" +
                    "ekMbBXDSi9JmO/+/hZcQZdF9WShwMydB1x+4zWBGC43InjB+kbIRVoc9hmR1PAtg\n" +
                    "nvEFEsHcmWiJgrEL8zHTk41ub12juiMBvZZb3cxlnYTzCVDLPSZWkL10eXZCkG3O\n" +
                    "CUQQarnmDLYxf3VB0Py6QhdL1ZdKU6lp5GZoGHpiSTZ2L+LLgpKX+S+2PiKnADob\n" +
                    "h9ZAsg4A4iIalQFGfAaZ55HTAvfI1eDrbVB/NXxI4ov8mriBc8EhSk1X9A1dQTKO\n" +
                    "usVnhUmv4UfcEVRLmJTizYa8pPYRT9zsAOIQcwIDAQABAoIBAEtU5FIR94tPvqrV\n" +
                    "7xl+DbdlCjFSKN5UkDRVr2pXBmAHegzu/Y5jiFzLSu2i+NZSQOGrew7tRfun5Z6G\n" +
                    "lneZJ4U0ZTvER0cu9z4o8SoJ0MuCjuOMrJfzsTPJgoEtBtVQKXxZyTiRx8rkhDbt\n" +
                    "h5nV3XarSNkPbDhE7iSSPfBkLAsZlsnVLvJFjTm3+szWDmlM9+M+u14fm3R+Fpjh\n" +
                    "EqZt815y6dZTN+oyIQU2bb1gvNS+fKr2ZEWSqj7bhuzDXWzgf8rhaNKmigG4NtkX\n" +
                    "hR696zbuxI0u1SR6Ffb57JW2xcWntURbgN9NVR4VssNECtekthav6BLRZMuVPBua\n" +
                    "XmVSiCECgYEA+JB/FZyITZtdyY4afgAb0HrWlf3jgK92OBFn+CWYfqvKja4b7S0V\n" +
                    "0ErrzIiAY7b17YoZfRWEx0OH1mb5PbkauHhhb29ZZ5cFzlpAaCRLUdF9RymhMLSP\n" +
                    "ozuCA2ot8vh/EAts4DkhRZwwV8AWBMc9iWh+063YBXRLYTNvenmTuG0CgYEAyb3U\n" +
                    "jDn7wYRtvSGZ1wNl8bhnFH5u7r0MXCwoOARveRUwyEhp+1M5bTpB4ltKA15kAWZV\n" +
                    "HmLPGXOqeD20w9RS/D7eiUu9GwPp7aGMyRpEQOI1adqF7ti4LjTVuxyAyT/WWMna\n" +
                    "6vlFBFbEbBGGNHQk1srmqIJaqMHPyO0xK9AvIF8CgYEA+BM+wtL3Nn1ZnU+2IQr6\n" +
                    "t5fhktFRvZ1g35R/r6nWCJZsEfsy5AObQceEjx2tBdgUmn658Z0IZ9d+Ov2Kw496\n" +
                    "m6GJnS4EjN6tbMWmgkm24nGyFtP1jCapNMCvgbj3IwffyKOehip+inrXxLxxPVOc\n" +
                    "lwczjRB0CP2IPfqXyCSOygECgYAZn6bTeLnlRnC5yP7FNIVKQmW1UKm+YPyk6Gbj\n" +
                    "VoziEDL1/VyYs2Vj3jZoDbhE3UROeTTuexZa4ToRs6S2Cs3PhBy3y4rlV4XqzM4Y\n" +
                    "7OEmbJTkMQE56QTbuZI8Bc7FwPn0pQ7NMYP2nR6tqzwkhWv4bCUH2iaxsIw3tQi9\n" +
                    "y99weQKBgQCslvmzJ8uBk8nitxTyLptjtM+YswJ+6DJDyNDxIn3ir/WFnzZQUcEA\n" +
                    "gPtuVvkQTJaR1B2/CaZGjxlDfOUDGl03K/MBUTarPPlnLNZVPYsH6BCbhgSU8VrX\n" +
                    "2Y8J3FJtY/jnx5+UItOQ9BgMYCzg6tVnib7gMQwpQ6nkegT+k/qJHA==\n" +
                    "-----END RSA PRIVATE KEY-----");
            Session session = sshService.connectToServer(serverConfig);
            String string = sshService.readRemoteFile(session, "/etc/sing-box/config.json");
            System.out.println(string);
        } catch (IOException | JSchException | SftpException e) {
            throw new RuntimeException(e);
        }
    }

}