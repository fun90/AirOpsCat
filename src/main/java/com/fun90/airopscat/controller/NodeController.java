package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.dto.NodeRequest;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.service.deployment.NodeDeploymentService;
import com.fun90.airopscat.service.NodeService;
import com.fun90.airopscat.service.ServerService;
import com.fun90.airopscat.service.TagService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
@Path("/api/admin/nodes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NodeController {
    
    @Inject
    NodeService nodeService;
    
    @Inject
    ServerService serverService;
    
    @Inject
    NodeDeploymentService nodeDeploymentService;
    
    @Inject
    TagService tagService;

    @GET
    public Response getNodePage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("serverId") Long serverId,
            @QueryParam("type") Integer type,
            @QueryParam("disabled") Boolean disabled
    ) {
        PanacheQuery<Node> nodeQuery = nodeService.getNodePage(search, serverId, type, disabled);
        nodeQuery.page(Page.of(page - 1, size));
        
        // Convert to DTOs
        List<NodeDto> nodeDtos = nodeQuery.list().stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", nodeDtos);
        response.put("total", nodeQuery.count());
        response.put("pages", nodeQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", nodeService.getNodesStats());

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getNodeById(@PathParam("id") Long id) {
        Node node = nodeService.getNodeById(id);
        if (node != null) {
            NodeDto dto = NodeConverter.toDto(node);
            return Response.ok(dto).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/server/{serverId}")
    public Response getNodesByServer(@PathParam("serverId") Long serverId) {
        List<Node> nodes = nodeService.getNodesByServer(serverId);
        List<NodeDto> nodeDtos = nodes.stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());
        return Response.ok(nodeDtos).build();
    }

    @GET
    @Path("/landing")
    public Response getLandingNodes() {
        List<Node> nodes = nodeService.getNodeByType(NodeType.LANDING);
        List<NodeDto> nodeDtos = nodes.stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());
        return Response.ok(nodeDtos).build();
    }
    
    @GET
    @Path("/types")
    public Response getNodeTypes() {
        return Response.ok(nodeService.getNodeTypeOptions()).build();
    }
    
    @GET
    @Path("/core-types")
    public Response getNodeCoreTypes() {
        return Response.ok(nodeService.getNodeCoreTypeOptions()).build();
    }

    @GET
    @Path("/protocols")
    public Response getProtocolTypes() {
        return Response.ok(nodeService.getProtocolTypeOptions()).build();
    }
    
    @GET
    @Path("/stats")
    public Response getNodesStats() {
        return Response.ok(nodeService.getNodesStats()).build();
    }
    
    @GET
    @Path("/available-port")
    public Response getAvailablePort(@QueryParam("serverId") Long serverId) {
        Map<String, Integer> response = new HashMap<>();
        response.put("port", nodeService.getAvailablePort(serverId));
        return Response.ok(response).build();
    }
    
    @GET
    @Path("/default-inbound")
    public Response getDefaultInbound(@QueryParam("protocol") String protocol,
                                      @QueryParam("serverId") Long serverId,
                                      @QueryParam("coreType") @DefaultValue("xray") String coreType) {
        return Response.ok(nodeService.generateDefaultInbound(protocol, serverId, coreType)).build();
    }
    
    @GET
    @Path("/check-port")
    public Response checkPortAvailability(
            @QueryParam("serverId") Long serverId,
            @QueryParam("backupServerId") Long backupServerId,
            @QueryParam("port") Integer port,
            @QueryParam("nodeId") Long nodeId
    ) {
        Map<String, Object> response = new HashMap<>();
        
        // 检查主服务器端口
        boolean mainServerAvailable = nodeService.isPortAvailable(serverId, port, nodeId);
        response.put("mainServerAvailable", mainServerAvailable);
        
        // 检查备用服务器端口
        boolean backupServerAvailable = nodeService.isBackupPortAvailable(backupServerId, port, nodeId);
        response.put("backupServerAvailable", backupServerAvailable);
        
        // 综合检查结果
        boolean available = nodeService.isNodePortsAvailable(serverId, backupServerId, port, nodeId);
        response.put("available", available);
        
        // 如果不可用，提供详细信息
        if (!available) {
            if (backupServerId != null && serverId.equals(backupServerId)) {
                response.put("message", "主服务器和备用服务器不能相同");
            } else if (!mainServerAvailable) {
                response.put("message", "端口在主服务器上已被占用");
            } else if (!backupServerAvailable) {
                response.put("message", "端口在备用服务器上已被占用");
            }
        }
        
        return Response.ok(response).build();
    }

    @POST
    public Response createNode(NodeRequest request) {
        try {
            // 使用 NodeConverter 转换请求为实体
            Node node = NodeConverter.fromRequest(request);
            Node savedNode = nodeService.saveNode(node);
            
            // 处理标签关联
            if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
                tagService.updateNodeTags(savedNode.getId(), request.getTagIds());
            }
            
            return Response.ok(NodeConverter.toDto(savedNode)).build();
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updateNode(@PathParam("id") Long id, NodeRequest request) {
        try {
            // 使用 NodeConverter 转换请求为实体，并设置 ID
            Node node = NodeConverter.fromRequest(request, id);
            Set<Tag> tagSet = new HashSet<>();
            if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
                tagSet = request.getTagIds().stream().map(tid -> tagService.getTagById(tid)).collect(Collectors.toSet());
            }
            Node updatedNode = nodeService.updateNode(node, tagSet);
            
            // 处理标签关联
            if (request.getTagIds() != null) {
                tagService.updateNodeTags(updatedNode.getId(), request.getTagIds());
            }
            
            return Response.ok(NodeConverter.toDto(updatedNode)).build();
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteNode(@PathParam("id") Long id) {
        Node existingNode = nodeService.getNodeById(id);
        if (existingNode == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        nodeService.deleteNode(id);
        return Response.ok().build();
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enableNode(@PathParam("id") Long id) {
        Node node = nodeService.toggleNodeStatus(id, false);
        if (node != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            response.put("deployed", 0);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disableNode(@PathParam("id") Long id) {
        Node node = nodeService.toggleNodeStatus(id, true);
        if (node != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            response.put("deployed", 0);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/servers")
    public Response getServers() {
        List<Server> servers = serverService.getAllActiveServers();
        List<Map<String, Object>> serverOptions = servers.stream()
                .map(server -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", server.getId());
                    option.put("name", (server.getName() != null ? server.getName() : "") +
                              " (" + server.getIp() + ")");
                    option.put("ip", server.getIp());
                    option.put("host", server.getHost());
                    return option;
                })
                .collect(Collectors.toList());
        return Response.ok(serverOptions).build();
    }

    @POST
    @Path("/{id}/copy")
    public Response copyNode(@PathParam("id") Long id) {
        Node existingNode = nodeService.getNodeById(id);
        if (existingNode == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        // Create a copy of the node manually to avoid shared references
        Node nodeCopy = new Node();
        
        // Copy basic properties manually
        nodeCopy.setServerId(existingNode.getServerId());
        nodeCopy.setProtocol(existingNode.getProtocol());
        nodeCopy.setType(existingNode.getType());
        nodeCopy.setInbound(existingNode.getInbound());
        nodeCopy.setOutId(existingNode.getOutId());
        nodeCopy.setRule(existingNode.getRule());
        nodeCopy.setLevel(existingNode.getLevel());
        nodeCopy.setDisabled(existingNode.getDisabled());
        nodeCopy.setRemark(existingNode.getRemark());
        
        // Set deployed status to 0 (not deployed) for the copy
        nodeCopy.setDeployed(0);

        // Modify the name to indicate it's a copy
        if (existingNode.getName() != null) {
            nodeCopy.setName(existingNode.getName() + " (Copy)");
        } else {
            nodeCopy.setName("Copy of Node " + id);
        }

        // The port must be unique per server, so get a new available port
        if (nodeCopy.getServerId() != null) {
            Integer availablePort = nodeService.getAvailablePort(nodeCopy.getServerId());
            nodeCopy.setPort(availablePort);
        }

        // Don't copy tags - let the new node start without any tags
        // Don't copy server/outNode references - they will be loaded by JPA automatically

        // Save the new node
        Node savedNode = nodeService.saveNode(nodeCopy);
        return Response.ok(savedNode).build();
    }

    @POST
    @Path("/{id}/deploy")
    public Response deployNode(@PathParam("id") Long id) {
        List<DeploymentResult> results = nodeDeploymentService.deployNodes(Collections.singletonList(id));
        return Response.ok(results.isEmpty() ? new DeploymentResult(id, null, false, "无需重复部署") : results.getFirst()).build();
    }

    @POST
    @Path("/{id}/deployForcibly")
    public Response deployNodeForcibly(@PathParam("id") Long id) {
        Node node = nodeService.getNodeById(id);
        if (node == null) {
            return Response.ok(new DeploymentResult(id, null, false, "节点不存在")).build();
        }
        List<DeploymentResult> results = nodeDeploymentService.deployNodesForcibly(Collections.singletonList(node));
        return Response.ok(results.isEmpty() ? new DeploymentResult(id, null, false, "无需部署") : results.getFirst()).build();
    }

    @POST
    @Path("/deploy-batch")
    public Response deployNodes(List<Long> nodeIds) {
        List<DeploymentResult> results = nodeDeploymentService.deployNodes(nodeIds);
        return Response.ok(results).build();
    }
}
