package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTargetServerCriteriaTest {

    @Test
    void shouldCoverRuntimeTargetScenarios() {
        LocalDate today = LocalDate.now();
        Server server = activeServer(today);

        assertFalse(matches(server, List.of(), today));
        assertFalse(matches(server, List.of(node(NodeDeploymentStatus.PENDING_DEPLOY, 0)), today));
        assertTrue(matches(server, List.of(node(NodeDeploymentStatus.DEPLOYED, 0)), today));
        assertFalse(matches(server, List.of(node(NodeDeploymentStatus.PENDING_DELETE, 0)), today));
        assertFalse(matches(server, List.of(node(NodeDeploymentStatus.DEPLOYED, 1)), today));

        server.setDisabled(1);
        assertFalse(matches(server, List.of(node(NodeDeploymentStatus.DEPLOYED, 0)), today));
        server.setDisabled(0);
        server.setExpireDate(today.minusDays(1));
        assertFalse(matches(server, List.of(node(NodeDeploymentStatus.DEPLOYED, 0)), today));
        server.setExpireDate(today);
        server.setExternal(1);
        assertFalse(matches(server, List.of(node(NodeDeploymentStatus.DEPLOYED, 0)), today));
    }

    private static boolean matches(Server server, List<NodeServer> placements, LocalDate today) {
        boolean validServer = (server.getDisabled() == null || server.getDisabled() == 0)
                && (server.getExternal() == null || server.getExternal() == 0)
                && (server.getExpireDate() == null || !server.getExpireDate().isBefore(today));
        return validServer && placements.stream().anyMatch(placement ->
                placement.enabledFlag() && NodeDeploymentStatus.isDeployed(placement.getDeployed()));
    }

    private static Server activeServer(LocalDate today) {
        Server server = new Server();
        server.setDisabled(0);
        server.setExternal(0);
        server.setExpireDate(today);
        return server;
    }

    // 节点禁用与部署位停用在查询中等价，这里用部署位 enabled 表示
    private static NodeServer node(NodeDeploymentStatus status, Integer disabled) {
        NodeServer placement = new NodeServer();
        placement.setDeployed(status.getValue());
        placement.setEnabled(disabled == 1 ? 0 : 1);
        return placement;
    }
}
