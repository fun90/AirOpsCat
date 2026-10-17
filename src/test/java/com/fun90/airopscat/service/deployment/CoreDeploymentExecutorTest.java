package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.repository.NodeServerRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreDeploymentExecutorTest {

    @Test
    void shouldPersistDeploymentResultWithoutSavingLargeConfigBody() {
        TestNodeServerRepository nodeServerRepository = new TestNodeServerRepository();
        TestNodeDeploymentVersionService versionService = new TestNodeDeploymentVersionService();
        CoreDeploymentExecutor executor = new CoreDeploymentExecutor(
                null, nodeServerRepository, null, versionService, null, null, null, null, null);

        Server server = new Server();
        server.setId(7L);
        NodeServer placement = new NodeServer();
        placement.setId(9L);
        placement.setNodeId(8L);
        placement.setServerId(7L);
        placement.setDeployed(NodeDeploymentStatus.PENDING_DEPLOY.getValue());
        String largeConfig = "x".repeat(70 * 1024);

        List<DeploymentResult> results = executor.persist(
                CoreDeploymentExecution.success(server, "sing-box", List.of(placement), largeConfig));

        assertEquals(1, results.size());
        assertTrue(results.getFirst().isSuccess());
        assertEquals(8L, results.getFirst().getNodeId());
        assertEquals(NodeDeploymentStatus.DEPLOYED.getValue(), placement.getDeployed());
        assertEquals(List.of(placement), nodeServerRepository.persisted);
        assertEquals(List.of(placement), versionService.recordedPlacements);
    }

    static class TestNodeServerRepository extends NodeServerRepository {
        final List<NodeServer> persisted = new ArrayList<>();

        @Override
        public void persist(NodeServer placement) {
            persisted.add(placement);
        }
    }

    static class TestNodeDeploymentVersionService extends NodeDeploymentVersionService {
        List<NodeServer> recordedPlacements = List.of();

        TestNodeDeploymentVersionService() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void recordSuccessfulDeployments(List<NodeServer> placements) {
            recordedPlacements = placements;
        }
    }
}
