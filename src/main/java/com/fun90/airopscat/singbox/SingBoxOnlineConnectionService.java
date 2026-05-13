package com.fun90.airopscat.singbox;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.singbox.NodeOnlineConnectionRecord;
import com.fun90.airopscat.model.dto.singbox.NodeOnlineConnectionsSnapshot;
import com.fun90.airopscat.model.dto.singbox.SingBoxConnectionMetadata;
import com.fun90.airopscat.model.dto.singbox.SingBoxConnectionSnapshot;
import com.fun90.airopscat.model.dto.singbox.SingBoxConnectionsResponse;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.AccountOnlineLimitAlertService;
import com.fun90.airopscat.service.AccountOnlineIpService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.model.dto.SshConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class SingBoxOnlineConnectionService {

    private static final String DEFAULT_USERNAME = "root";
    private static final int SNAPSHOT_SCHEMA_VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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

    @Inject
    SystemConfigService systemConfigService;

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
            List<SingBoxConnectionSnapshot> connections = getRefreshConnections(connection, server);
            if (connections == null || connections.isEmpty()) {
                return 0;
            }
            Map<String, Node> nodeByTag = nodeRepository.findOnlineTrackableByServerId(server.getId()).stream()
                    .collect(Collectors.toMap(Node::getTag, node -> node, (left, right) -> left));
            return accountOnlineIpService.refreshFromConnections(server.getIp(), connections, nodeByTag);
        }
    }

    private List<SingBoxConnectionSnapshot> getRefreshConnections(SshConnection connection, Server server) throws Exception {
        if (isSnapshotEnabled()) {
            try {
                List<SingBoxConnectionSnapshot> snapshotConnections = readOnlineSnapshot(connection);
                log.debug("在线账号刷新使用节点快照: server={}({}), connections={}",
                        server.getName(), server.getId(), snapshotConnections.size());
                return snapshotConnections;
            } catch (Exception e) {
                if (!isSnapshotFallbackEnabled()) {
                    throw e;
                }
                log.warn("读取节点在线快照失败，降级到 Clash API: server={}({}), error={}",
                        server.getName(), server.getId(), e.getMessage());
            }
        }

        SingBoxConnectionsResponse response = clashApiClient.getConnections(connection);
        if (response == null || response.getConnections() == null) {
            return List.of();
        }
        log.debug("在线账号刷新使用 Clash API: server={}({}), connections={}",
                server.getName(), server.getId(), response.getConnections().size());
        return response.getConnections();
    }

    private List<SingBoxConnectionSnapshot> readOnlineSnapshot(SshConnection connection) throws IOException {
        String path = getOnlineSnapshotPath();
        String content = connection.readRemoteFile(path);
        NodeOnlineConnectionsSnapshot snapshot = MAPPER.readValue(content, NodeOnlineConnectionsSnapshot.class);
        validateSnapshot(snapshot, path);
        return toConnectionSnapshots(snapshot.getConnections());
    }

    private void validateSnapshot(NodeOnlineConnectionsSnapshot snapshot, String path) throws IOException {
        if (snapshot == null) {
            throw new IOException("在线快照为空: " + path);
        }
        if (!Objects.equals(snapshot.getSchemaVersion(), SNAPSHOT_SCHEMA_VERSION)) {
            throw new IOException("在线快照 schema 不支持: " + snapshot.getSchemaVersion());
        }
        long generatedAt = snapshot.getGeneratedAtEpochSeconds() == null ? 0L : snapshot.getGeneratedAtEpochSeconds();
        int ttlSeconds = snapshot.getTtlSeconds() == null ? 0 : snapshot.getTtlSeconds();
        long now = Instant.now().getEpochSecond();
        if (generatedAt <= 0 || ttlSeconds <= 0 || now - generatedAt > ttlSeconds) {
            throw new IOException("在线快照已过期: age=" + (now - generatedAt) + "s, ttl=" + ttlSeconds + "s");
        }
        if (snapshot.getConnections() == null) {
            throw new IOException("在线快照 connections 为空: " + path);
        }
    }

    private List<SingBoxConnectionSnapshot> toConnectionSnapshots(List<NodeOnlineConnectionRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<SingBoxConnectionSnapshot> result = new ArrayList<>(records.size());
        for (NodeOnlineConnectionRecord record : records) {
            if (record == null) {
                continue;
            }
            SingBoxConnectionSnapshot snapshot = new SingBoxConnectionSnapshot();
            snapshot.setId(record.getId());
            snapshot.setStart(record.getStart());

            SingBoxConnectionMetadata metadata = new SingBoxConnectionMetadata();
            metadata.setAuthUser(record.getAccountNo());
            metadata.setSourceIP(record.getClientIp());
            if (record.getNodeTag() != null && !record.getNodeTag().isBlank()) {
                metadata.setType("snapshot/" + record.getNodeTag().trim());
            }
            snapshot.setMetadata(metadata);
            result.add(snapshot);
        }
        return result;
    }

    private boolean isSnapshotEnabled() {
        return systemConfigService.getBooleanValue("airopscat.connection-snapshot.online.enabled", true);
    }

    private boolean isSnapshotFallbackEnabled() {
        return systemConfigService.getBooleanValue("airopscat.connection-snapshot.online.fallback-clash-api-enabled", true);
    }

    private String getOnlineSnapshotPath() {
        String path = systemConfigService.getResolvedValue("airopscat.connection-snapshot.online.path");
        return path == null || path.isBlank() ? "/run/airopscat/online-connections.json" : path.trim();
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
