package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.service.NodeService;
import com.fun90.airopscat.service.ServerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/nodes")
public class NodeController {
    
    private final NodeService nodeService;
    private final ServerService serverService;
    
    @Autowired
    public NodeController(NodeService nodeService, ServerService serverService) {
        this.nodeService = nodeService;
        this.serverService = serverService;
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
                .map(node -> nodeService.convertToDto(node))
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
            NodeDto dto = nodeService.convertToDto(node);
            return ResponseEntity.ok(dto);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/server/{serverId}")
    public ResponseEntity<List<NodeDto>> getNodesByServer(@PathVariable Long serverId) {
        List<Node> nodes = nodeService.getNodesByServer(serverId);
        List<NodeDto> nodeDtos = nodes.stream()
                .map(node -> nodeService.convertToDto(node))
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
    public ResponseEntity<?> createNode(@RequestBody Node node) {
        try {

            Node savedNode = nodeService.saveNode(node);
            return ResponseEntity.ok(savedNode);
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateNode(@PathVariable Long id, @RequestBody Node node) {
        Node existingNode = nodeService.getNodeById(id);
        if (existingNode == null) {
            return ResponseEntity.notFound().build();
        }

        node.setId(id);
        try {
            Node updatedNode = nodeService.updateNode(node);
            return ResponseEntity.ok(updatedNode);
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
}