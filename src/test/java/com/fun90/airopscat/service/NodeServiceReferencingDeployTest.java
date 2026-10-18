package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.ServerRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeServiceReferencingDeployTest {

    private static final int DEPLOYED = NodeDeploymentStatus.DEPLOYED.getValue();
    private static final int PENDING = NodeDeploymentStatus.PENDING_DEPLOY.getValue();

    private final Node landing = node(1L, NodeType.LANDING, null);
    private final Node proxy = node(2L, NodeType.PROXY, 1L);
    private final NodeServer landingPlacement = placement(11L, 1L);
    private final NodeServer proxyPlacement = placement(21L, 2L);
    private final NodeService service = service();

    @Test
    void landingConfigChangeMarksReferencingProxies() {
        Node update = node(1L, NodeType.LANDING, null);
        update.setPort(8443);

        service.updateNode(update, null, Set.of(), false);

        assertEquals(PENDING, landingPlacement.getDeployed());
        assertEquals(PENDING, proxyPlacement.getDeployed());
    }

    @Test
    void landingTagChangeDoesNotMarkReferencingProxies() {
        Tag tag = new Tag();
        tag.setId(5L);

        service.updateNode(node(1L, NodeType.LANDING, null), null, Set.of(tag), true);

        assertEquals(PENDING, landingPlacement.getDeployed());
        assertEquals(DEPLOYED, proxyPlacement.getDeployed());
    }

    @Test
    void togglingLandingMarksReferencingProxies() {
        service.toggleNodeStatus(1L, true);

        assertEquals(PENDING, landingPlacement.getDeployed());
        assertEquals(PENDING, proxyPlacement.getDeployed());
    }

    private NodeService service() {
        Map<Long, Node> nodes = Map.of(1L, landing, 2L, proxy);
        Map<Long, List<NodeServer>> placements = Map.of(1L, List.of(landingPlacement), 2L, List.of(proxyPlacement));
        NodeService nodeService = new NodeService();
        nodeService.nodeRepository = new NodeRepository() {
            @Override
            public Node findById(Long id) {
                return nodes.get(id);
            }

            @Override
            public List<Node> findByOutId(Long outId) {
                return nodes.values().stream().filter(node -> outId.equals(node.getOutId())).toList();
            }
        };
        nodeService.nodeServerRepository = new NodeServerRepository() {
            @Override
            public List<NodeServer> findByNodeId(Long nodeId) {
                return placements.getOrDefault(nodeId, List.of());
            }

            @Override
            public boolean existsPortConflict(Long serverId, Integer port, Long excludeNodeId) {
                return false;
            }
        };
        nodeService.serverRepository = new ServerRepository() {
            @Override
            public Server findById(Long id) {
                return new Server();
            }
        };
        nodeService.placementSyncService = new NodePlacementSyncService() {
            @Override
            public void syncNode(Node node, boolean strict) {
            }
        };
        return nodeService;
    }

    private static Node node(Long id, NodeType type, Long outId) {
        Node node = new Node();
        node.setId(id);
        node.setName("节点-1");
        node.setType(type.getValue());
        node.setProtocol("shadowsocks");
        node.setPort(443);
        node.setRegion("香港");
        node.setOutId(outId);
        node.setTags(new java.util.HashSet<>());
        return node;
    }

    private static NodeServer placement(Long id, Long nodeId) {
        NodeServer placement = new NodeServer();
        placement.setId(id);
        placement.setNodeId(nodeId);
        placement.setServerId(100L + nodeId);
        placement.setDeployed(DEPLOYED);
        return placement;
    }
}
