package com.fun90.airopscat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.SshConfig;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.OutboundConfig;
import com.fun90.airopscat.model.dto.xray.XrayConfig;
import com.fun90.airopscat.model.dto.xray.routing.RoutingRule;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.model.entity.ServerNode;
import com.fun90.airopscat.model.enums.CoreOperation;
import com.fun90.airopscat.model.enums.CoreType;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerNodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.core.CoreManagementService;
import com.fun90.airopscat.service.xray.registry.ConversionStrategyRegistry;
import com.fun90.airopscat.service.xray.strategy.ConversionStrategy;
import com.fun90.airopscat.utils.ConfigFileReader;
import com.fun90.airopscat.utils.JsonUtil;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NodeDeploymentService {

    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final ServerNodeRepository serverNodeRepository;
    private final ServerConfigRepository serverConfigRepository;
    private final ObjectMapper objectMapper;
    private final ConversionStrategyRegistry strategyRegistry;
    private final CoreManagementService coreManagementService;

    @Autowired
    public NodeDeploymentService(
            NodeRepository nodeRepository,
            ServerRepository serverRepository,
            ServerNodeRepository serverNodeRepository,
            ServerConfigRepository serverConfigRepository,
            ObjectMapper objectMapper,
            ConversionStrategyRegistry strategyRegistry,
            CoreManagementService coreManagementService) {
        this.nodeRepository = nodeRepository;
        this.serverRepository = serverRepository;
        this.serverNodeRepository = serverNodeRepository;
        this.serverConfigRepository = serverConfigRepository;
        this.objectMapper = objectMapper;
        this.strategyRegistry = strategyRegistry;
        this.coreManagementService = coreManagementService;
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

        // 第一步：生成节点配置
        // 找出未部署的节点
        List<Node> unDeployedNodes = nodeRepository.findByDeployed(0);
        // 按服务器分组
        Map<Long, List<Node>> serverNodeMap = unDeployedNodes.stream()
                .collect(Collectors.groupingBy(Node::getServerId));
        serverNodeMap.forEach((serverId, nodes) -> {
            // 按protocol找到对应的内核，按内核分组
            Map<String, List<Node>> coreTypeNodeMap = nodes.stream()
                    .collect(Collectors.groupingBy(o -> getCoreType(o.getProtocol())));
            for (Map.Entry<String, List<Node>> nodeListEntry : coreTypeNodeMap.entrySet()) {
                String coreType = nodeListEntry.getKey();
                List<Node> coreNodes = nodeListEntry.getValue();

                // 查询服务器配置，未查到则新建
                Optional<ServerConfig> serverConfigOptional = serverConfigRepository.findByServerIdAndConfigType(serverId, coreType);
                ServerConfig serverConfig = serverConfigOptional.orElseGet(() -> {
                    ServerConfig newServerConfig = new ServerConfig();
                    newServerConfig.setServerId(serverId);
                    newServerConfig.setConfigType(coreType);
                    newServerConfig.setConfig(ConfigFileReader.readFileContent("config/xray/xray-template.json"));
                    return newServerConfig;
                });

                if (coreType.equalsIgnoreCase(CoreType.XRAY.name())) {
                    XrayConfig xrayConfig = JsonUtil.toObject(serverConfig.getConfig(), XrayConfig.class);
                    coreNodes.forEach(node -> {
                        if (node.getDisabled() == 1) {
                            xrayConfig.getInbounds().removeIf(inbound -> inbound.getTag().equals(node.getTag()));
                        } else {
                            List<InboundConfig> inbounds = xrayConfig.getInbounds();
                            if (inbounds == null) {
                                inbounds = new ArrayList<>();
                                xrayConfig.setInbounds(inbounds);
                            }
                            InboundConfig inboundConfig = inbounds.stream()
                                    .filter(o -> o.getTag().equals(node.getTag()))
                                    .findFirst().orElse(null);
                            if (inboundConfig == null) {
                                inboundConfig = JsonUtil.toObject(node.getInbound(), InboundConfig.class);
                                inboundConfig.setTag(node.getTag());
                                inbounds.add(inboundConfig);
                            } else {
                                InboundConfig newInboundConfig = JsonUtil.toObject(node.getInbound(), InboundConfig.class);
                                newInboundConfig.setTag(node.getTag());
                                // 使用newInboundConfig来替换inboundConfig
                                int index = inbounds.indexOf(inboundConfig);
                                inbounds.set(index, newInboundConfig);
                            }

                            // 处理outbound
                            Node outNode = node.getOutNode();
                            if (outNode != null) {
                                String outboundProtocol = outNode.getProtocol();
                                ConversionStrategy strategy = strategyRegistry.getStrategy(outboundProtocol);
                                // 验证配置
                                InboundConfig inboundConfigOfOutNode = JsonUtil.toObject(outNode.getInbound(), InboundConfig.class);
                                if (!strategy.validate(inboundConfigOfOutNode)) {
                                    throw new IllegalArgumentException(
                                            String.format("Invalid inbound configuration for protocol: %s", outboundProtocol));
                                }
                                try {
                                    OutboundConfig newOutboundConfig = strategy.convert(inboundConfigOfOutNode, outNode.getServer().getHost(), outNode.getPort());
                                    newOutboundConfig.setTag(outNode.getTag());

                                    log.debug("Successfully converted {} configuration", outboundProtocol);
                                    OutboundConfig outboundConfig = xrayConfig.getOutbounds().stream()
                                            .filter(o -> o.getTag().equals(outNode.getTag()))
                                            .findFirst().orElse(null);
                                    if (outboundConfig != null) {
                                        int index = xrayConfig.getOutbounds().indexOf(outboundConfig);
                                        xrayConfig.getOutbounds().set(index, newOutboundConfig);
                                        RoutingRule routingRule = xrayConfig.getRouting().getRules().stream().filter(rule -> rule.getInboundTag().contains(node.getTag()))
                                                .findFirst().orElseThrow(() -> new IllegalArgumentException("Routing rule not found"));
                                        routingRule.setOutboundTag(outNode.getTag());
                                        int routingRuleIndex = xrayConfig.getRouting().getRules().indexOf(routingRule);
                                        xrayConfig.getRouting().getRules().set(routingRuleIndex, routingRule);
                                    } else {
                                        xrayConfig.getOutbounds().add(newOutboundConfig);
                                        RoutingRule routingRule = new RoutingRule();
                                        routingRule.setInboundTag(Collections.singletonList(node.getTag()));
                                        routingRule.setOutboundTag(outNode.getTag());
                                        routingRule.setType("field");
                                        xrayConfig.getRouting().getRules().add(routingRule);
                                    }
                                } catch (Exception e) {
                                    log.error("Failed to convert {} configuration: {}", outboundProtocol, e.getMessage(), e);
                                    throw new RuntimeException(
                                            String.format("Failed to convert %s configuration: %s", outboundProtocol, e.getMessage()), e);
                                }
                            }
                        }

                        // 添加节点部署结果
                        DeploymentResult result = new DeploymentResult();
                        result.setNodeId(node.getId());
                        result.setServerId(node.getServerId());
                        result.setSuccess(true);
                        results.add(result);
                    });

                    serverConfig.setConfig(JsonUtil.toJsonString(xrayConfig));
                }

                // 将配置保存到数据库
                serverConfigRepository.save(serverConfig);

                // 使用SSH重启Xray
//                sshService.restartXray(server.getHost(), server.getPort(), server.getPassword());
                Server server = serverRepository.findById(serverId).orElseThrow(() -> new IllegalArgumentException("Server not found"));
                SshConfig sshConfig = new SshConfig();
                sshConfig.setHost(server.getIp());
                sshConfig.setPort(server.getSshPort());
                sshConfig.setUsername(server.getUsername());
                sshConfig.setPassword(server.getAuth());
                coreManagementService.executeOperation(coreType, CoreOperation.RESTART, sshConfig);
            }
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