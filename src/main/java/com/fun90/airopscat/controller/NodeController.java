package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.dto.NodeRequest;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.service.NodeDeploymentService;
import com.fun90.airopscat.service.NodeService;
import com.fun90.airopscat.service.ServerService;
import com.fun90.airopscat.service.TagService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/nodes")
public class NodeController {
    
    private final NodeService nodeService;
    private final ServerService serverService;
    private final NodeDeploymentService nodeDeploymentService;
    private final TagService tagService;
    
    @Autowired
    public NodeController(NodeService nodeService, ServerService serverService, NodeDeploymentService nodeDeploymentService, TagService tagService) {
        this.nodeService = nodeService;
        this.serverService = serverService;
        this.nodeDeploymentService = nodeDeploymentService;
        this.tagService = tagService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getNodePage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long serverId,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) Boolean disabled
    ) {
        Page<Node> nodePage = nodeService.getNodePage(page, size, search, serverId, type, disabled);
        
        // Convert to DTOs
        List<NodeDto> nodeDtos = nodePage.getContent().stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", nodeDtos);
        response.put("total", nodePage.getTotalElements());
        response.put("pages", nodePage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", nodeService.getNodesStats());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<NodeDto> getNodeById(@PathVariable Long id) {
        Node node = nodeService.getNodeById(id);
        if (node != null) {
            NodeDto dto = NodeConverter.toDto(node);
            return ResponseEntity.ok(dto);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/server/{serverId}")
    public ResponseEntity<List<NodeDto>> getNodesByServer(@PathVariable Long serverId) {
        List<Node> nodes = nodeService.getNodesByServer(serverId);
        List<NodeDto> nodeDtos = nodes.stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(nodeDtos);
    }

    @GetMapping("/landing")
    public ResponseEntity<List<NodeDto>> getLandingNodes() {
        List<Node> nodes = nodeService.getNodeByType(NodeType.LANDING);
        List<NodeDto> nodeDtos = nodes.stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(nodeDtos);
    }
    
    @GetMapping("/types")
    public ResponseEntity<List<Map<String, Object>>> getNodeTypes() {
        return ResponseEntity.ok(nodeService.getNodeTypeOptions());
    }
    
    @GetMapping("/protocols")
    public ResponseEntity<List<Map<String, Object>>> getProtocolTypes() {
        return ResponseEntity.ok(nodeService.getProtocolTypeOptions());
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getNodesStats() {
        return ResponseEntity.ok(nodeService.getNodesStats());
    }
    
    @GetMapping("/available-port")
    public ResponseEntity<Map<String, Integer>> getAvailablePort(@RequestParam Long serverId) {
        Map<String, Integer> response = new HashMap<>();
        response.put("port", nodeService.getAvailablePort(serverId));
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/default-inbound")
    public ResponseEntity<Map<String, Object>> getDefaultInbound(@RequestParam String protocol) {
        return ResponseEntity.ok(nodeService.generateDefaultInbound(protocol));
    }
    
    @GetMapping("/check-port")
    public ResponseEntity<Map<String, Boolean>> checkPortAvailability(
            @RequestParam Long serverId,
            @RequestParam Integer port,
            @RequestParam(required = false) Long nodeId
    ) {
        Map<String, Boolean> response = new HashMap<>();
        response.put("available", nodeService.isPortAvailable(serverId, port, nodeId));
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<?> createNode(@RequestBody NodeRequest request) {
        try {
            // 创建Node实体
            Node node = new Node();
            node.setServerId(request.getServerId());
            node.setPort(request.getPort());
            node.setProtocol(request.getProtocol());
            node.setType(request.getType());
            node.setInbound(request.getInbound() != null ? 
                com.fun90.airopscat.utils.JsonUtil.toJsonString(request.getInbound()) : null);
            node.setOutId(request.getOutId());
            node.setRule(request.getRule() != null ? 
                com.fun90.airopscat.utils.JsonUtil.toJsonString(request.getRule()) : null);
            node.setLevel(request.getLevel());
            node.setDisabled(request.getDisabled());
            node.setName(request.getName());
            node.setRemark(request.getRemark());

            Node savedNode = nodeService.saveNode(node);
            
            // 处理标签关联
            if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
                tagService.updateNodeTags(savedNode.getId(), request.getTagIds());
            }
            
            return ResponseEntity.ok(savedNode);
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateNode(@PathVariable Long id, @RequestBody NodeRequest request) {
        try {
            // 创建Node实体
            Node node = new Node();
            node.setId(id);
            node.setServerId(request.getServerId());
            node.setPort(request.getPort());
            node.setProtocol(request.getProtocol());
            node.setType(request.getType());
            node.setInbound(request.getInbound() != null ? 
                com.fun90.airopscat.utils.JsonUtil.toJsonString(request.getInbound()) : null);
            node.setOutId(request.getOutId());
            node.setRule(request.getRule() != null ? 
                com.fun90.airopscat.utils.JsonUtil.toJsonString(request.getRule()) : null);
            node.setLevel(request.getLevel());
            node.setDisabled(request.getDisabled());
            node.setName(request.getName());
            node.setRemark(request.getRemark());
            
            Node updatedNode = nodeService.updateNode(node);
            
            // 处理标签关联
            if (request.getTagIds() != null) {
                tagService.updateNodeTags(updatedNode.getId(), request.getTagIds());
            }
            
            return ResponseEntity.ok(NodeConverter.toDto(updatedNode));
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNode(@PathVariable Long id) {
        Node existingNode = nodeService.getNodeById(id);
        if (existingNode == null) {
            return ResponseEntity.notFound().build();
        }

        nodeService.deleteNode(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableNode(@PathVariable Long id) {
        Node node = nodeService.toggleNodeStatus(id, false);
        if (node != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            response.put("deployed", 0);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableNode(@PathVariable Long id) {
        Node node = nodeService.toggleNodeStatus(id, true);
        if (node != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            response.put("deployed", 0);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/servers")
    public ResponseEntity<List<Map<String, Object>>> getServers() {
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
        return ResponseEntity.ok(serverOptions);
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<Node> copyNode(@PathVariable Long id) {
        Node existingNode = nodeService.getNodeById(id);
        if (existingNode == null) {
            return ResponseEntity.notFound().build();
        }

        // Create a copy of the node
        Node nodeCopy = new Node();
        // Copy all properties except ID (which will be auto-generated)
        BeanUtils.copyProperties(existingNode, nodeCopy, "id", "createTime", "updateTime");

        // Modify the name to indicate it's a copy
        if (nodeCopy.getName() != null) {
            nodeCopy.setName(nodeCopy.getName() + " (Copy)");
        } else {
            nodeCopy.setName("Copy of Node " + id);
        }

        // The port must be unique per server, so get a new available port
        if (nodeCopy.getServerId() != null) {
            Integer availablePort = nodeService.getAvailablePort(nodeCopy.getServerId());
            nodeCopy.setPort(availablePort);
        }

        // Save the new node
        Node savedNode = nodeService.saveNode(nodeCopy);
        return ResponseEntity.ok(savedNode);
    }

    @PostMapping("/{id}/deploy")
    public ResponseEntity<DeploymentResult> deployNode(@PathVariable Long id) {
        List<DeploymentResult> results = nodeDeploymentService.deployNodes(Collections.singletonList(id));
        return ResponseEntity.ok(results.isEmpty() ? new DeploymentResult(id, null, false, "无需重复部署") : results.getFirst());
    }

    @PostMapping("/{id}/deployForcibly")
    public ResponseEntity<DeploymentResult> deployNodeForcibly(@PathVariable Long id) {
        Node node = nodeService.getNodeById(id);
        if (node == null) {
            return ResponseEntity.ok(new DeploymentResult(id, null, false, "节点不存在"));
        }
        List<DeploymentResult> results = nodeDeploymentService.deployNodesForcibly(Collections.singletonList(node));
        return ResponseEntity.ok(results.isEmpty() ? new DeploymentResult(id, null, false, "无需部署") : results.getFirst());
    }

    @PostMapping("/deploy-batch")
    public ResponseEntity<List<DeploymentResult>> deployNodes(@RequestBody List<Long> nodeIds) {
        List<DeploymentResult> results = nodeDeploymentService.deployNodes(nodeIds);
        return ResponseEntity.ok(results);
    }
}