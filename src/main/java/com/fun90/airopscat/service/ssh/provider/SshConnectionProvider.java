package com.fun90.airopscat.service.ssh.provider;

import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.service.ssh.SshConnection;

import java.io.IOException;

@FunctionalInterface
public interface SshConnectionProvider {
    SshConnection createConnection(SshConfig config) throws IOException;
}