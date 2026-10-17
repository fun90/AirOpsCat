package com.fun90.airopscat.model.dto.deployment;

import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
 
import java.util.List;
 
public record CoreDeploymentExecution(
        Server server,
        String coreType,
        List<NodeServer> placements,
        boolean success,
        String config,
        String message
) {
    public static CoreDeploymentExecution success(Server server, String coreType,
                                                   List<NodeServer> placements, String config) {
        return new CoreDeploymentExecution(server, coreType, placements, true, config, null);
    }
 
    public static CoreDeploymentExecution failure(Server server, String coreType,
                                                   List<NodeServer> placements, String message) {
        return new CoreDeploymentExecution(server, coreType, placements, false, null, message);
    }
}