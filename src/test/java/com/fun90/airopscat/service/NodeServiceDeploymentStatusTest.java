package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeServiceDeploymentStatusTest {

    @Test
    void shouldMarkNormalNodeAsPendingDeploy() {
        NodeService service = new NodeService();
        Node node = new Node();
        node.setDeployed(NodeDeploymentStatus.DEPLOYED.getValue());

        service.markPendingDeploy(node);

        assertEquals(NodeDeploymentStatus.PENDING_DEPLOY.getValue(), node.getDeployed());
    }

    @Test
    void shouldNotOverwritePendingDeleteStatusWhenMarkingPendingDeploy() {
        NodeService service = new NodeService();
        Node node = new Node();
        node.setDeployed(NodeDeploymentStatus.PENDING_DELETE.getValue());

        service.markPendingDeploy(node);

        assertEquals(NodeDeploymentStatus.PENDING_DELETE.getValue(), node.getDeployed());
    }
}
