package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.NodeBatchDeleteRequest;
import com.fun90.airopscat.model.dto.NodeBatchTagUpdateRequest;
import com.fun90.airopscat.model.dto.NodeDeploymentRestoreRequest;
import com.fun90.airopscat.model.dto.NodeDeploymentVersionDetailDto;
import com.fun90.airopscat.model.dto.NodeDeploymentVersionDto;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.dto.NodeRequest;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.service.AccountOnlineIpService;
import com.fun90.airopscat.service.deployment.NodeDeploymentService;
import com.fun90.airopscat.service.deployment.NodeDeploymentVersionService;
import com.fun90.airopscat.service.NodeService;
import com.fun90.airopscat.service.ServerService;
import com.fun90.airopscat.service.TagService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
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

    @Inject
    NodeDeploymentVersionService nodeDeploymentVersionService;

    @Inject
    AccountOnlineIpService accountOnlineIpService;

    @GET
    public Response getNodePage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("serverId") String serverIds,
            @QueryParam("node_tag") Long nodeTagId,
            @QueryParam("type") Integer type,
            @QueryParam("coreType") String coreType,
            @QueryParam("protocol") String protocol,
            @QueryParam("disabled") Boolean disabled,
            @QueryParam("deployed") Boolean deployed,
            @QueryParam("deploymentStatus") Integer deploymentStatus,
            @QueryParam("sortBy") String sortBy,
            @QueryParam("sortOrder") @DefaultValue("desc") String sortOrder
    ) {
        PanacheQuery<Node> nodeQuery = nodeService.getNodePage(search, serverIds, nodeTagId, type, coreType, protocol, disabled, deployed, deploymentStatus, sortBy, sortOrder);
        nodeQuery.page(Page.of(page - 1, size));
        List<NodeDto> nodeDtos = nodeService.toNodeDtos(nodeQuery.list());
        nodeService.fillOnlineConnectionCounts(nodeDtos);

        Map<String, Object> response = new HashMap<>();
        response.put("records", nodeDtos);
        response.put("total", nodeQuery.count());
        response.put("pages", nodeQuery.pageCount());
        response.put("current", page);
        response.put("size", size);

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}/online-accounts")
    public Response getNodeOnlineAccounts(@PathParam("id") Long id) {
        Node node = nodeService.getNodeById(id);
        if (node == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(accountOnlineIpService.getOnlineRecordsByNodeId(id)).build();
    }

    @GET
    @Path("/{id}")
    public Response getNodeById(@PathParam("id") Long id) {
        NodeDto dto = nodeService.getNodeDtoById(id);
        if (dto != null) {
            return Response.ok(dto).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/server/{serverId}")
    public Response getNodesByServer(@PathParam("serverId") Long serverId) {
        List<Node> nodes = nodeService.getNodesByServer(serverId);
        List<NodeDto> nodeDtos = nodeService.toNodeDtos(nodes);
        return Response.ok(nodeDtos).build();
    }

    @GET
    @Path("/landing")
    public Response getLandingNodes() {
        List<Node> nodes = nodeService.getNodeByType(NodeType.LANDING);
        List<NodeDto> nodeDtos = nodeService.toNodeDtos(nodes);
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
    
    /** 按地区和类型预览会自动关联的服务器（新建节点时用） */
    @GET
    @Path("/matching-servers")
    public Response getMatchingServers(@QueryParam("region") String region, @QueryParam("type") Integer type) {
        return Response.ok(nodeService.findMatchingServers(region, type).stream()
                .map(server -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", server.getId());
                    option.put("name", server.getName());
                    option.put("ip", server.getIp());
                    return option;
                })
                .toList()).build();
    }

    @GET
    @Path("/available-port")
    public Response getAvailablePort(@QueryParam("serverId") String serverIds) {
        List<Long> ids = serverIds == null ? List.of() : Arrays.stream(serverIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(Long::parseLong)
                .toList();
        Map<String, Integer> response = new HashMap<>();
        response.put("port", nodeService.getAvailablePort(ids));
        return Response.ok(response).build();
    }
    
    @GET
    @Path("/default-inbound")
    public Response getDefaultInbound(@QueryParam("protocol") String protocol,
                                      @QueryParam("serverId") Long serverId,
                                      @QueryParam("accessHostId") Long accessHostId,
                                      @QueryParam("coreType") @DefaultValue("sing-box") String coreType) {
        return Response.ok(nodeService.generateDefaultInbound(protocol, serverId, accessHostId, coreType)).build();
    }
    
    @GET
    @Path("/check-port")
    public Response checkPortAvailability(
            @QueryParam("serverId") Long serverId,
            @QueryParam("port") Integer port,
            @QueryParam("nodeId") Long nodeId
    ) {
        Map<String, Object> response = new HashMap<>();

        boolean available = nodeService.isNodePortsAvailable(serverId, port, nodeId);
        response.put("available", available);
        if (!available) {
            response.put("message", "端口在当前服务器上已被占用");
        }
        return Response.ok(response).build();
    }

    @POST
    public Response createNode(NodeRequest request) {
        try {
            // 使用 NodeConverter 转换请求为实体
            Node node = NodeConverter.fromRequest(request);
            Node savedNode = nodeService.saveNode(node, request.getPlacements());
            
            // 处理标签关联
            if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
                tagService.updateNodeTags(savedNode.getId(), request.getTagIds());
            }
            
            return Response.ok(nodeService.getNodeDtoById(savedNode.getId())).build();
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
            Node updatedNode = nodeService.updateNode(node, request.getPlacements(), tagSet, request.getTagIds() != null);
            
            // 处理标签关联
            if (request.getTagIds() != null) {
                tagService.updateNodeTags(updatedNode.getId(), request.getTagIds());
            }
            
            return Response.ok(nodeService.getNodeDtoById(updatedNode.getId())).build();
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

        try {
            Integer deployed = nodeService.deleteNode(id);
            String message = deployed != null
                    ? "节点已标记为待删除，部署成功后将从服务器移除"
                    : "删除成功";
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("deployed", deployed);
            response.put("message", message);
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enableNode(@PathParam("id") Long id) {
        Node node = nodeService.toggleNodeStatus(id, false);
        if (node != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            response.put("deployed", NodeDeploymentStatus.PENDING_DEPLOY.getValue());
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
            response.put("deployed", NodeDeploymentStatus.PENDING_DEPLOY.getValue());
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
                    option.put("region", server.getRegion());
                    option.put("serverType", server.getServerType());
                    return option;
                })
                .collect(Collectors.toList());
        return Response.ok(serverOptions).build();
    }

    @POST
    @Path("/{id}/copy")
    public Response copyNode(@PathParam("id") Long id) {
        try {
            Node existingNode = nodeService.getNodeById(id);
            if (existingNode == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            // Create a copy of the node manually to avoid shared references
            Node nodeCopy = new Node();
            nodeCopy.setProtocol(existingNode.getProtocol());
            nodeCopy.setCoreType(existingNode.getCoreType());
            nodeCopy.setType(existingNode.getType());
            nodeCopy.setInbound(existingNode.getInbound());
            nodeCopy.setOutId(existingNode.getOutId());
            nodeCopy.setRule(existingNode.getRule());
            nodeCopy.setLevel(existingNode.getLevel());
            nodeCopy.setDisabled(existingNode.getDisabled());
            nodeCopy.setRemark(existingNode.getRemark());
            nodeCopy.setName(existingNode.getName() != null ? existingNode.getName() + " (Copy)" : "Copy of Node " + id);

            // 部署位由地区和类型自动关联；端口需在所有匹配的服务器上可用，域名不复制（一条记录只能被一个部署位引用）
            nodeCopy.setRegion(existingNode.getRegion());
            nodeCopy.setPort(nodeService.getAvailablePort(nodeService.findMatchingServers(
                    existingNode.getRegion(), existingNode.getType()).stream().map(Server::getId).toList()));

            Node savedNode = nodeService.saveNode(nodeCopy, null);
            return Response.ok(nodeService.getNodeDtoById(savedNode.getId())).build();
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @POST
    @Path("/{id}/deploy")
    public Response deployNode(@PathParam("id") Long id) {
        List<DeploymentResult> results = nodeDeploymentService.deployNodes(Collections.singletonList(id));
        return Response.ok(toSingleDeployResponse(id, results, "无需重复部署")).build();
    }

    @POST
    @Path("/{id}/deployForcibly")
    public Response deployNodeForcibly(@PathParam("id") Long id) {
        Node node = nodeService.getNodeById(id);
        if (node == null) {
            return Response.ok(new DeploymentResult(id, null, false, "节点不存在")).build();
        }
        List<DeploymentResult> results = nodeDeploymentService.deployNodesForcibly(Collections.singletonList(node));
        return Response.ok(toSingleDeployResponse(id, results, "无需部署")).build();
    }

    @POST
    @Path("/deploy-batch")
    public Response deployNodes(List<Long> nodeIds) {
        List<DeploymentResult> results = nodeDeploymentService.deployNodes(nodeIds);
        return Response.ok(results).build();
    }

    @POST
    @Path("/batch-delete")
    public Response batchDeleteNodes(NodeBatchDeleteRequest request) {
        try {
            Map<String, Integer> result = nodeService.batchDeleteNodes(request == null ? null : request.getNodeIds());
            int pending = result.getOrDefault("pendingCount", 0);
            return Response.ok(Map.of(
                    "message", pending > 0
                            ? "已删除 " + result.get("deletedCount") + " 个节点，" + pending + " 个已部署的节点标记为待删除，部署成功后将从服务器移除"
                            : "已删除 " + result.get("deletedCount") + " 个节点",
                    "deletedCount", result.getOrDefault("deletedCount", 0),
                    "pendingCount", pending
            )).build();
        } catch (IllegalArgumentException | EntityNotFoundException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @POST
    @Path("/batch-tags")
    public Response batchUpdateNodeTags(NodeBatchTagUpdateRequest request) {
        try {
            Map<String, Integer> result = nodeService.batchUpdateNodeTags(
                    request == null ? null : request.getNodeIds(),
                    request == null ? null : request.getTagIds(),
                    request == null ? null : request.resolveMode());
            return Response.ok(Map.of(
                    "message", "批量调整节点标签成功",
                    "updatedCount", result.getOrDefault("updatedCount", 0),
                    "unchangedCount", result.getOrDefault("unchangedCount", 0)
            )).build();
        } catch (IllegalArgumentException | EntityNotFoundException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @GET
    @Path("/{id}/deployment-versions")
    public Response getDeploymentVersions(@PathParam("id") Long id) {
        try {
            List<NodeDeploymentVersionDto> versions = nodeDeploymentVersionService.listVersions(id);
            return Response.ok(versions).build();
        } catch (IllegalArgumentException | EntityNotFoundException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @GET
    @Path("/{id}/deployment-versions/{version}")
    public Response getDeploymentVersionDetail(@PathParam("id") Long id,
                                               @PathParam("version") Integer version,
                                               @QueryParam("nodeServerId") Long nodeServerId) {
        try {
            NodeDeploymentVersionDetailDto detail = nodeDeploymentVersionService.getVersionDetail(id, nodeServerId, version);
            return Response.ok(detail).build();
        } catch (IllegalArgumentException | EntityNotFoundException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @POST
    @Path("/{id}/deployment-versions/{version}/restore")
    public Response restoreDeploymentVersion(@PathParam("id") Long id,
                                             @PathParam("version") Integer version,
                                             @QueryParam("nodeServerId") Long nodeServerId,
                                             NodeDeploymentRestoreRequest request) {
        try {
            Node restoredNode = nodeDeploymentVersionService.restoreVersion(id, nodeServerId, version);
            if (request != null && Boolean.TRUE.equals(request.getRedeploy())) {
                nodeDeploymentService.deployNodesForcibly(Collections.singletonList(restoredNode));
            }
            return Response.ok(Map.of(
                    "message", "版本还原成功",
                    "nodeId", id,
                    "version", version,
                    "deployed", NodeDeploymentStatus.PENDING_DEPLOY.getValue()
            )).build();
        } catch (IllegalArgumentException | EntityNotFoundException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    private DeploymentResult toSingleDeployResponse(Long requestedNodeId, List<DeploymentResult> results, String emptyMessage) {
        if (results == null || results.isEmpty()) {
            return new DeploymentResult(requestedNodeId, null, false, emptyMessage);
        }

        DeploymentResult requestedNodeResult = results.stream()
                .filter(result -> Objects.equals(result.getNodeId(), requestedNodeId))
                .findFirst()
                .orElse(results.getFirst());

        if (results.size() == 1) {
            return requestedNodeResult;
        }

        long successCount = results.stream().filter(DeploymentResult::isSuccess).count();
        long failureCount = results.size() - successCount;
        boolean overallSuccess = failureCount == 0 && requestedNodeResult.isSuccess();
        String summary = String.format("共部署 %d 个部署位，成功 %d 个，失败 %d 个。", results.size(), successCount, failureCount);
        String detailMessage = requestedNodeResult.getMessage();
        String mergedMessage = detailMessage == null || detailMessage.isBlank()
                ? summary
                : detailMessage + "；" + summary;

        return new DeploymentResult(
                requestedNodeResult.getNodeId(),
                requestedNodeResult.getServerId(),
                overallSuccess,
                mergedMessage
        );
    }
}
