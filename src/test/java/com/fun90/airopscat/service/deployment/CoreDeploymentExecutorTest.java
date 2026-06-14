package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.repository.NodeRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreDeploymentExecutorTest {

    @Test
    void shouldPersistDeploymentResultWithoutSavingLargeConfigBody() {
        TestNodeRepository nodeRepository = new TestNodeRepository();
        TestNodeDeploymentVersionService versionService = new TestNodeDeploymentVersionService();
        CoreDeploymentExecutor executor = new CoreDeploymentExecutor(
                null, nodeRepository, null, versionService, null, null, null, null, null);

        Server server = new Server();
        server.setId(7L);
        Node node = new Node();
        node.setId(8L);
        node.setServerId(7L);
        node.setDeployed(NodeDeploymentStatus.PENDING_DEPLOY.getValue());
        nodeRepository.nodes.put(node.getId(), node);
        String largeConfig = "x".repeat(70 * 1024);

        List<DeploymentResult> results = executor.persist(
                CoreDeploymentExecution.success(server, "sing-box", List.of(node), largeConfig));

        assertEquals(1, results.size());
        assertTrue(results.getFirst().isSuccess());
        assertEquals(NodeDeploymentStatus.DEPLOYED.getValue(), node.getDeployed());
        assertEquals(List.of(node), versionService.recordedNodes);
    }

    static class TestNodeRepository extends NodeRepository {
        final Map<Long, Node> nodes = new HashMap<>();

        @Override
        public void persist(Node node) {
            nodes.put(node.getId(), node);
        }

        @Override
        public Node findById(Long id) {
            return nodes.get(id);
        }
    }

    static class TestNodeDeploymentVersionService extends NodeDeploymentVersionService {
        List<Node> recordedNodes = List.of();

        TestNodeDeploymentVersionService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void recordSuccessfulDeployments(List<Node> nodes) {
            recordedNodes = nodes;
        }
    }
}
