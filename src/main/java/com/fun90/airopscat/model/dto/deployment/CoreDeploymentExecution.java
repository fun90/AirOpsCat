package com.fun90.airopscat.model.dto.deployment;

import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
 
import java.util.List;
 
public record CoreDeploymentExecution(
        Server server,
        String coreType,
        List<Node> nodes,
        boolean success,
        String config,
        String message
) {
    public static CoreDeploymentExecution success(Server server, String coreType,
                                                   List<Node> nodes, String config) {
        return new CoreDeploymentExecution(server, coreType, nodes, true, config, null);
    }
 
    public static CoreDeploymentExecution failure(Server server, String coreType,
                                                   List<Node> nodes, String message) {
        return new CoreDeploymentExecution(server, coreType, nodes, false, null, message);
    }
}