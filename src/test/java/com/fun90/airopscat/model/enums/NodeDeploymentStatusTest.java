package com.fun90.airopscat.model.enums;

import com.fun90.airopscat.model.dto.NodeDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDeploymentStatusTest {

    @Test
    void shouldMapLegacyValuesToDeploymentStatuses() {
        assertEquals(NodeDeploymentStatus.PENDING_DEPLOY, NodeDeploymentStatus.fromValue(0));
        assertEquals(NodeDeploymentStatus.DEPLOYED, NodeDeploymentStatus.fromValue(1));
        assertEquals(NodeDeploymentStatus.PENDING_DELETE, NodeDeploymentStatus.fromValue(2));
    }

    @Test
    void shouldTreatUnknownOrNullValueAsPendingDeploy() {
        assertEquals(NodeDeploymentStatus.PENDING_DEPLOY, NodeDeploymentStatus.fromValue(null));
        assertEquals(NodeDeploymentStatus.PENDING_DEPLOY, NodeDeploymentStatus.fromValue(99));
        assertTrue(NodeDeploymentStatus.isPendingDeploy(null));
    }

    @Test
    void shouldExposeChineseStatusDescriptionsThroughNodeDto() {
        NodeDto dto = new NodeDto();

        dto.setDeployed(NodeDeploymentStatus.PENDING_DEPLOY.getValue());
        assertEquals("待部署", dto.getDeploymentStatusDescription());

        dto.setDeployed(NodeDeploymentStatus.PENDING_DELETE.getValue());
        assertEquals("待删除", dto.getDeploymentStatusDescription());

        dto.setDeployed(NodeDeploymentStatus.DEPLOYED.getValue());
        assertEquals("已部署", dto.getDeploymentStatusDescription());
    }
}
