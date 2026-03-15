package com.fun90.airopscat.model.dto.deployment;
 
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
 
import java.util.List;
import java.util.Map;
 
public record DeploymentServerContext(
        Server server,
        List<Node> nodes,
        ServerSnapshot serverSnapshot,
        Map<Long, XrayNodeSnapshot> xraySnapshotMap
) {}
 