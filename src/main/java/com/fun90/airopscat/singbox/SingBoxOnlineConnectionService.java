package com.fun90.airopscat.singbox;

import com.fun90.airopscat.model.dto.singbox.SingBoxConnectionsResponse;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.AccountOnlineLimitAlertService;
import com.fun90.airopscat.service.AccountOnlineIpService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.model.dto.SshConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class SingBoxOnlineConnectionService {

    private static final String DEFAULT_USERNAME = "root";

    @Inject
    ServerRepository serverRepository;

    @Inject
    NodeRepository nodeRepository;

    @Inject
    SingBoxClashApiClient clashApiClient;

    @Inject
    SshConnectionService sshConnectionService;

    @Inject
    AccountOnlineIpService accountOnlineIpService;

    @Inject
    AccountOnlineLimitAlertService accountOnlineLimitAlertService;

    public void refreshAllServers() {
        List<Server> servers = serverRepository.findMonitorableServers(LocalDate.now());
        if (servers.isEmpty()) {
            log.debug("没有可采集的服务器，跳过在线账号刷新");
            return;
        }

        int success = 0, failure = 0;
        for (Server server : servers) {
            try {
                int count = refreshServer(server);
                log.debug("在线账号采集完成: server={}({}), upsert={}", server.getName(), server.getId(), count);
                success++;
            } catch (Exception e) {
                failure++;
                log.warn("在线账号采集失败，跳过该服务器: server={}({}), error={}",
                        server.getName(), server.getId(), e.getMessage());
            }
        }
        log.info("在线账号刷新完成，服务器数={}, 成功={}, 失败={}", servers.size(), success, failure);
        accountOnlineLimitAlertService.checkAndNotify();
    }

    private int refreshServer(Server server) throws Exception {
        try (SshConnection connection = sshConnectionService.createConnection(buildSshConfig(server))) {
            SingBoxConnectionsResponse response = clashApiClient.getConnections(connection);
            if (response == null || response.getConnections() == null) {
                return 0;
            }
            Map<String, Node> nodeByTag = nodeRepository.findOnlineTrackableByServerId(server.getId()).stream()
                    .collect(Collectors.toMap(Node::getTag, node -> node, (left, right) -> left));
            return accountOnlineIpService.refreshFromConnections(server.getIp(), response.getConnections(), nodeByTag);
        }
    }

    private SshConfig buildSshConfig(Server server) {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setHost(server.getIp());
        sshConfig.setPort(server.getSshPort());
        sshConfig.setUsername(Objects.toString(server.getUsername(), DEFAULT_USERNAME));

        boolean isPassword = "PASSWORD".equalsIgnoreCase(server.getAuthType())
                || "password".equalsIgnoreCase(server.getAuthType());
        if (isPassword) {
            sshConfig.setPassword(server.getAuth());
        } else {
            sshConfig.setPrivateKeyContent(server.getAuth());
        }
        return sshConfig;
    }

    public SingBoxConnectionsResponse getServerConnections(Server server) throws Exception {
        try (SshConnection connection = sshConnectionService.createConnection(buildSshConfig(server))) {
            return clashApiClient.getConnections(connection);
        }
    }

    public void deleteServerConnection(Server server, String connectionId) throws Exception {
        try (SshConnection connection = sshConnectionService.createConnection(buildSshConfig(server))) {
            clashApiClient.deleteConnection(connection, connectionId);
        }
    }

    public void deleteAllServerConnections(Server server) throws Exception {
        try (SshConnection connection = sshConnectionService.createConnection(buildSshConfig(server))) {
            clashApiClient.deleteAllConnections(connection);
        }
    }
}
