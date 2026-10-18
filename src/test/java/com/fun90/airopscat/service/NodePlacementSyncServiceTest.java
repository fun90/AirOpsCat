package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.repository.NodeDeploymentRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.ServerRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodePlacementSyncServiceTest {

    private static final int PENDING = NodeDeploymentStatus.PENDING_DEPLOY.getValue();
    private static final int DEPLOYED = NodeDeploymentStatus.DEPLOYED.getValue();
    private static final int PENDING_DELETE = NodeDeploymentStatus.PENDING_DELETE.getValue();

    private final List<Server> servers = new ArrayList<>();
    private final List<NodeServer> placements = new ArrayList<>();
    private final List<NodeServer> deleted = new ArrayList<>();
    private final List<Long> conflictServerIds = new ArrayList<>();
    private final NodePlacementSyncService service = service();

    @Test
    void proxyNodeMatchesOnlySameRegionProxyServers() {
        Node node = node(1L, NodeType.PROXY, "美国");

        assertTrue(NodePlacementSyncService.matches(node, server(1L, "美国", NodeType.PROXY, 0, 0)));
        assertTrue(NodePlacementSyncService.matches(node, server(2L, "美国", null, 0, 0)));
        assertFalse(NodePlacementSyncService.matches(node, server(3L, "美国", NodeType.LANDING, 0, 0)));
        assertFalse(NodePlacementSyncService.matches(node, server(4L, "日本", NodeType.PROXY, 0, 0)));
        assertFalse(NodePlacementSyncService.matches(node, server(5L, "美国", NodeType.PROXY, 0, 1)));
        assertFalse(NodePlacementSyncService.matches(node, server(6L, null, NodeType.PROXY, 0, 0)));
    }

    @Test
    void landingNodeMatchesLandingAndProxyServers() {
        Node node = node(1L, NodeType.LANDING, "美国");

        assertTrue(NodePlacementSyncService.matches(node, server(1L, "美国", NodeType.PROXY, 0, 0)));
        assertTrue(NodePlacementSyncService.matches(node, server(2L, "美国", NodeType.LANDING, 0, 0)));
        assertFalse(NodePlacementSyncService.matches(node, server(3L, "日本", NodeType.LANDING, 0, 0)));
    }

    @Test
    void createsPlacementsForMatchingEnabledServersAfterExistingPriority() {
        Node node = node(1L, NodeType.PROXY, "美国");
        servers.add(server(1L, "美国", NodeType.PROXY, 0, 0));
        servers.add(server(2L, "美国", NodeType.PROXY, 0, 0));
        servers.add(server(3L, "美国", NodeType.PROXY, 1, 0));
        servers.add(server(4L, "日本", NodeType.PROXY, 0, 0));
        NodeServer existing = placement(10L, 1L, 1L, 5, DEPLOYED);
        existing.setExposed(0);
        placements.add(existing);

        service.syncNode(node, true);

        assertEquals(2, placements.size());
        NodeServer created = placements.get(1);
        assertEquals(2L, created.getServerId());
        assertEquals(6, created.getPriority());
        assertEquals(1, created.getExposed());
        assertEquals(PENDING, created.getDeployed());
        // 已有部署位的属性不被同步改动
        assertEquals(0, existing.getExposed());
        assertEquals(5, existing.getPriority());
    }

    @Test
    void keepsPlacementOnDisabledServerButDoesNotCreateNew() {
        Node node = node(1L, NodeType.PROXY, "美国");
        servers.add(server(1L, "美国", NodeType.PROXY, 1, 0));
        placements.add(placement(10L, 1L, 1L, 1, DEPLOYED));

        service.syncNode(node, true);

        assertEquals(1, placements.size());
        assertEquals(DEPLOYED, placements.get(0).getDeployed());
    }

    @Test
    void unmatchedPlacementIsMarkedPendingDeleteWhenDeployedElseDeleted() {
        Node node = node(1L, NodeType.PROXY, "美国");
        servers.add(server(1L, "日本", NodeType.PROXY, 0, 0));
        servers.add(server(2L, "日本", NodeType.PROXY, 0, 0));
        NodeServer deployed = placement(10L, 1L, 1L, 1, DEPLOYED);
        NodeServer notDeployed = placement(11L, 1L, 2L, 2, PENDING);
        placements.add(deployed);
        placements.add(notDeployed);

        service.syncNode(node, true);

        assertEquals(PENDING_DELETE, deployed.getDeployed());
        assertEquals(List.of(notDeployed), deleted);
    }

    @Test
    void rematchedPendingDeletePlacementIsRestored() {
        Node node = node(1L, NodeType.PROXY, "美国");
        servers.add(server(1L, "美国", NodeType.PROXY, 0, 0));
        NodeServer pendingDelete = placement(10L, 1L, 1L, 1, PENDING_DELETE);
        placements.add(pendingDelete);

        service.syncNode(node, true);

        assertEquals(PENDING, pendingDelete.getDeployed());
        assertEquals(1, placements.size());
    }

    @Test
    void nodeWithoutRegionIsLeftAlone() {
        Node node = node(1L, NodeType.PROXY, null);
        servers.add(server(1L, "美国", NodeType.PROXY, 0, 0));
        placements.add(placement(10L, 1L, 9L, 1, DEPLOYED));

        service.syncNode(node, true);

        assertEquals(1, placements.size());
        assertEquals(DEPLOYED, placements.get(0).getDeployed());
    }

    @Test
    void portConflictFailsStrictSyncAndIsSkippedOtherwise() {
        Node node = node(1L, NodeType.PROXY, "美国");
        servers.add(server(1L, "美国", NodeType.PROXY, 0, 0));
        conflictServerIds.add(1L);

        assertThrows(IllegalArgumentException.class, () -> service.syncNode(node, true));

        service.syncNode(node, false);
        assertTrue(placements.isEmpty());
    }

    private NodePlacementSyncService service() {
        NodePlacementSyncService sync = new NodePlacementSyncService() {
            @Override
            public void delete(NodeServer placement) {
                deleted.add(placement);
                placements.remove(placement);
            }
        };
        sync.serverRepository = new ServerRepository() {
            @Override
            public List<Server> listAll() {
                return servers;
            }
        };
        sync.nodeServerRepository = new NodeServerRepository() {
            @Override
            public List<NodeServer> findByNodeId(Long nodeId) {
                return new ArrayList<>(placements);
            }

            @Override
            public boolean existsPortConflict(Long serverId, Integer port, Long excludeNodeId) {
                return conflictServerIds.contains(serverId);
            }

            @Override
            public void persist(NodeServer entity) {
                placements.add(entity);
            }
        };
        sync.nodeDeploymentRepository = new NodeDeploymentRepository() {
            @Override
            public Optional<com.fun90.airopscat.model.entity.NodeDeployment> findByNodeServerId(Long nodeServerId) {
                return Optional.empty();
            }
        };
        return sync;
    }

    private static Node node(Long id, NodeType type, String region) {
        Node node = new Node();
        node.setId(id);
        node.setType(type.getValue());
        node.setRegion(region);
        node.setPort(443);
        return node;
    }

    private static Server server(Long id, String region, NodeType type, int disabled, int external) {
        Server server = new Server();
        server.setId(id);
        server.setName("s" + id);
        server.setRegion(region);
        server.setServerType(type == null ? null : type.getValue());
        server.setDisabled(disabled);
        server.setExternal(external);
        return server;
    }

    private static NodeServer placement(Long id, Long nodeId, Long serverId, int priority, int deployed) {
        NodeServer placement = new NodeServer();
        placement.setId(id);
        placement.setNodeId(nodeId);
        placement.setServerId(serverId);
        placement.setPriority(priority);
        placement.setExposed(1);
        placement.setDeployed(deployed);
        return placement;
    }
}
