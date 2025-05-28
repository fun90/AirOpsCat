package com.fun90.airopscat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.XrayConfig;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.entity.ServerNode;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerNodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.utils.JsonUtil;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class NodeDeploymentService {
    
    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final ServerNodeRepository serverNodeRepository;
    private final ServerConfigRepository serverConfigRepository;
    private final SshService sshService;
    private final ObjectMapper objectMapper;
    
    @Autowired
    public NodeDeploymentService(
            NodeRepository nodeRepository,
            ServerRepository serverRepository,
            ServerNodeRepository serverNodeRepository,
            ServerConfigRepository serverConfigRepository,
            SshService sshService,
            ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.serverRepository = serverRepository;
        this.serverNodeRepository = serverNodeRepository;
        this.serverConfigRepository = serverConfigRepository;
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
     * Deploy multiple nodes at once
     */
    @Transactional
    public List<DeploymentResult> deployNodes(List<Long> nodeIds) {
        List<DeploymentResult> results = new ArrayList<>();

        // 找出未部署的节点
        List<Node> nodeList = nodeRepository.findByDeployed(0);
        // 按服务器分组
        Map<Long, List<Node>> groupedNodes = nodeList.stream()
                .collect(Collectors.groupingBy(Node::getServerId));
        groupedNodes.forEach((serverId, nodes) -> {
            List<NodeDto> nodeDtos = nodes.stream()
                    .map(NodeConverter::toDto)
                    .toList();
            // 按protocol分类
            Map<String, List<NodeDto>> protocolNodeMap = nodeDtos.stream()
                    .collect(Collectors.groupingBy(NodeDto::getProtocol));
            protocolNodeMap.forEach((protocol, protocolNodes) -> {
                // 查询服务器配置，未查到则新建
                String coreType = getCoreType(protocol);
                Optional<ServerConfig> serverConfigOptional = serverConfigRepository.findByServerIdAndConfigType(serverId, coreType);
                ServerConfig serverConfig = serverConfigOptional.orElseGet(() -> {
                    ServerConfig newServerConfig = new ServerConfig();
                    newServerConfig.setServerId(serverId);
                    newServerConfig.setConfigType(coreType);
                    newServerConfig.setConfig("{}");
                    return newServerConfig;
                });

                if (coreType.equalsIgnoreCase(CoreType.XRAY.name())) {
                    XrayConfig xrayConfig = JsonUtil.toObject(serverConfig.getConfig(), XrayConfig.class);
                    protocolNodes.forEach(nodeDto -> {
                        if (nodeDto.getDisabled() == 1) {
                            xrayConfig.getInbounds().removeIf(inbound -> inbound.getTag().equals(nodeDto.getTag()));
                        } else {
                            List<InboundConfig> inbounds = xrayConfig.getInbounds();
                            if (inbounds == null) {
                                inbounds = new ArrayList<>();
                                xrayConfig.setInbounds(inbounds);
                            }
                            InboundConfig inboundConfig = inbounds.stream()
                                    .filter(o -> o.getTag().equals(nodeDto.getTag()))
                                    .findFirst().orElse(null);
                            if (inboundConfig == null) {
                                inboundConfig = objectMapper.convertValue(nodeDto.getInbound(), InboundConfig.class);
                                inboundConfig.setTag(nodeDto.getTag());
                                inboundConfig.setPort(nodeDto.getPort());
                                inbounds.add(inboundConfig);
                            }
                        }
                        System.out.println(JsonUtil.toJsonString(xrayConfig));
                        DeploymentResult result = new DeploymentResult();
                        result.setNodeId(nodeDto.getId());
                        result.setServerId(nodeDto.getServerId());
                        result.setSuccess(true);
                        results.add(result);
                    });
                }
            });
        });
        
        return results;
    }

    private String getCoreType(String protocol) {
        if (protocol.equalsIgnoreCase("hysteria2")) {
            return "hysteria";
        }
        return "xray";
    }
    
    /**
     * Create a ServerNode entity from a Node entity
     */
    private ServerNode createServerNodeFromNode(Node node) throws JsonProcessingException {
        ServerNode serverNode = new ServerNode();
        
        serverNode.setServerId(node.getServerId());
        serverNode.setId(node.getId());
        serverNode.setPort(node.getPort());
        
        // Extract protocol from setting configuration
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
        
        // Extract protocol from setting configuration
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