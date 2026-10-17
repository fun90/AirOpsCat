package com.fun90.airopscat.model.dto.deployment;
 
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
 
import java.util.List;
import java.util.Map;
 
public record DeploymentServerContext(
        Server server,
        List<NodeServer> placements,
        ServerSnapshot serverSnapshot,
        Map<Long, NodeDeploymentSnapshot> nodeSnapshotMap
) {}
 
