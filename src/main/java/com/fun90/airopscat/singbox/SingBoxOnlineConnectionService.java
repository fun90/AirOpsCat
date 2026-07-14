package com.fun90.airopscat.singbox;

import com.fun90.airopscat.model.dto.singbox.SingBoxConnectionsResponse;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.service.ssh.ServerSshConfigFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class SingBoxOnlineConnectionService {

    @Inject
    SingBoxClashApiClient clashApiClient;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    ServerSshConfigFactory serverSshConfigFactory;

    public SingBoxConnectionsResponse getServerConnections(Server server) throws Exception {
        try (SshConnection connection = sshConnectionService.createConnection(serverSshConfigFactory.create(server))) {
            return clashApiClient.getConnections(connection);
        }
    }

    public void deleteServerConnection(Server server, String connectionId) throws Exception {
        try (SshConnection connection = sshConnectionService.createConnection(serverSshConfigFactory.create(server))) {
            clashApiClient.deleteConnection(connection, connectionId);
        }
    }

    public void deleteAllServerConnections(Server server) throws Exception {
        try (SshConnection connection = sshConnectionService.createConnection(serverSshConfigFactory.create(server))) {
            clashApiClient.deleteAllConnections(connection);
        }
    }
}
