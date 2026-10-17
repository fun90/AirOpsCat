package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeServiceDeploymentStatusTest {

    @Test
    void sharedDnsRequiresIdenticalPlacementTags() {
        assertTrue(NodeService.sameTags(List.of(Set.of(1L, 2L), Set.of(2L, 1L))));
        assertFalse(NodeService.sameTags(List.of(Set.of(1L), Set.of(2L))));
    }

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
