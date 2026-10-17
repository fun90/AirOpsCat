package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeServiceDeploymentStatusTest {

    @Test
    void shouldMarkNormalPlacementAsPendingDeploy() {
        NodeService service = new NodeService();
        NodeServer placement = new NodeServer();
        placement.setDeployed(NodeDeploymentStatus.DEPLOYED.getValue());

        service.markPendingDeploy(placement);

        assertEquals(NodeDeploymentStatus.PENDING_DEPLOY.getValue(), placement.getDeployed());
    }

    @Test
    void shouldNotOverwritePendingDeleteStatusWhenMarkingPendingDeploy() {
        NodeService service = new NodeService();
        NodeServer placement = new NodeServer();
        placement.setDeployed(NodeDeploymentStatus.PENDING_DELETE.getValue());

        service.markPendingDeploy(placement);

        assertEquals(NodeDeploymentStatus.PENDING_DELETE.getValue(), placement.getDeployed());
    }
}
