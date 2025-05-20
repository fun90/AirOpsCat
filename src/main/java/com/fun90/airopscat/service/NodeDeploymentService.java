package com.fun90.airopscat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerNode;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerNodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class NodeDeploymentService {
    
    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final ServerNodeRepository serverNodeRepository;
    private final SshService sshService;
    private final ObjectMapper objectMapper;
    
    @Autowired
    public NodeDeploymentService(
            NodeRepository nodeRepository,
            ServerRepository serverRepository,
            ServerNodeRepository serverNodeRepository,
            SshService sshService,
            ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.serverRepository = serverRepository;
        this.serverNodeRepository = serverNodeRepository;
        this.sshService = sshService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Deploy a node to the server
     */
    @Transactional
    public DeploymentResult deployNode(Long nodeId) {
        // Get the node
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found"));
        
        // Get the server
        Server server = serverRepository.findById(node.getServerId())
                .orElseThrow(() -> new IllegalArgumentException("Server not found"));
        
        // Create deployment result
        DeploymentResult result = new DeploymentResult();
        result.setNodeId(nodeId);
        result.setServerId(server.getId());
        
        try {
            // Check if node is already deployed
            Optional<ServerNode> existingDeployment = serverNodeRepository.findById(nodeId);
            if (existingDeployment.isPresent()) {
                // Update existing deployment
                ServerNode serverNode = existingDeployment.get();
                updateServerNodeFromNode(serverNode, node);
                serverNodeRepository.save(serverNode);
                
                // TODO: Execute remote commands to update the deployed node configuration

                // Update node status
                node.setDeployed(1); // Set to deployed
                nodeRepository.save(node);
                
                result.setSuccess(true);
                result.setMessage("Node updated successfully");
            } else {
                // Create new deployment
                ServerNode serverNode = createServerNodeFromNode(node);
                serverNodeRepository.save(serverNode);
                
                // TODO: Execute remote commands to deploy the node configuration
                
                // Update node status
                node.setDeployed(1); // Set to deployed
                nodeRepository.save(node);
                
                result.setSuccess(true);
                result.setMessage("Node deployed successfully");
            }
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("Deployment failed: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Undeploy a node from the server
     */
    @Transactional
    public DeploymentResult undeployNode(Long nodeId) {
        // Get the node
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found"));
        
        DeploymentResult result = new DeploymentResult();
        result.setNodeId(nodeId);
        result.setServerId(node.getServerId());
        
        try {
            // Find all server nodes associated with this node
            List<ServerNode> deployments = serverNodeRepository.findByNodeId(nodeId);
            
            for (ServerNode deployment : deployments) {
                // TODO: Execute remote commands to remove the deployed node configuration
                
                // Delete the deployment record
                serverNodeRepository.delete(deployment);
            }
            
            // Update node status
            node.setDeployed(0); // Set to undeployed
            nodeRepository.save(node);
            
            result.setSuccess(true);
            result.setMessage("Node undeployed successfully");
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("Undeployment failed: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Deploy multiple nodes at once
     */
    @Transactional
    public List<DeploymentResult> deployNodes(List<Long> nodeIds) {
        List<DeploymentResult> results = new ArrayList<>();
        
        for (Long nodeId : nodeIds) {
            results.add(deployNode(nodeId));
        }
        
        return results;
    }
    
    /**
     * Create a ServerNode entity from a Node entity
     */
    private ServerNode createServerNodeFromNode(Node node) throws JsonProcessingException {
        ServerNode serverNode = new ServerNode();
        
        serverNode.setServerId(node.getServerId());
        serverNode.setId(node.getId());
        serverNode.setPort(node.getPort());
        
        // Extract protocol from inbound configuration
        if (node.getInbound() != null) {
            Map<String, Object> inbound = objectMapper.readValue(node.getInbound(), Map.class);
            serverNode.setProtocol((String) inbound.get("protocol"));
        }
        
        serverNode.setType(node.getType());
        serverNode.setInbound(node.getInbound());
        serverNode.setOutId(node.getOutId());
        serverNode.setRule(node.getRule());
        serverNode.setLevel(node.getLevel());
        serverNode.setDisabled(0);
        serverNode.setName(node.getName());
        serverNode.setRemark(node.getRemark());
        
        return serverNode;
    }
    
    /**
     * Update a ServerNode entity from a Node entity
     */
    private void updateServerNodeFromNode(ServerNode serverNode, Node node) throws JsonProcessingException {
        serverNode.setPort(node.getPort());
        
        // Extract protocol from inbound configuration
        if (node.getInbound() != null) {
            Map<String, Object> inbound = objectMapper.readValue(node.getInbound(), Map.class);
            serverNode.setProtocol((String) inbound.get("protocol"));
        }
        
        serverNode.setType(node.getType());
        serverNode.setInbound(node.getInbound());
        serverNode.setOutId(node.getOutId());
        serverNode.setRule(node.getRule());
        serverNode.setLevel(node.getLevel());
        serverNode.setDisabled(0);
        serverNode.setName(node.getName());
        serverNode.setRemark(node.getRemark());
    }
}