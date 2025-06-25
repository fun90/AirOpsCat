package com.fun90.airopscat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.model.enums.ProtocolType;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NodeService {

    private static final Logger log = LoggerFactory.getLogger(NodeService.class);
    private final NodeRepository nodeRepository;
    private final ServerRepository serverRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public NodeService(NodeRepository nodeRepository, 
                      ServerRepository serverRepository, 
                      ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.serverRepository = serverRepository;
        this.objectMapper = objectMapper;
    }

    public Page<Node> getNodePage(int page, int size, String search, Long serverId, Integer type, Boolean disabled) {
        // Create pageable with sorting (newest first)
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("createTime").descending());

        // Create specification for dynamic filtering
        Specification<Node> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            // Join with Server
            Join<Node, Server> serverJoin = root.join("server", JoinType.LEFT);

            // Search in name, remark, or server properties
            if (StringUtils.hasText(search)) {
                Predicate namePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate remarkPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("remark")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate serverIpPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(serverJoin.get("ip")),
                        "%" + search.toLowerCase() + "%"
                );
                Predicate serverHostPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(serverJoin.get("host")),
                        "%" + search.toLowerCase() + "%"
                );
                
                predicates.add(criteriaBuilder.or(
                        namePredicate, remarkPredicate, serverIpPredicate, serverHostPredicate
                ));
            }

            // Filter by serverId
            if (serverId != null) {
                predicates.add(criteriaBuilder.equal(root.get("serverId"), serverId));
            }

            // Filter by type
            if (type != null) {
                predicates.add(criteriaBuilder.equal(root.get("type"), type));
            }

            // Filter by disabled status
            if (disabled != null) {
                predicates.add(criteriaBuilder.equal(root.get("disabled"), disabled ? 1 : 0));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return nodeRepository.findAll(spec, pageable);
    }

    public Node getNodeById(Long id) {
        return nodeRepository.findById(id).orElse(null);
    }

    public List<Node> getNodeByType(NodeType nodeType) {
        return nodeRepository.findByType(nodeType.getValue());
    }

    public List<Node> getNodesByServer(Long serverId) {
        return nodeRepository.findByServerId(serverId);
    }

    public List<Node> getActiveNodesByServer(Long serverId) {
        return nodeRepository.findActiveNodesByServerId(serverId);
    }

    public Map<String, Long> getNodesStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", nodeRepository.count());
        stats.put("proxy", nodeRepository.countProxyNodes());
        stats.put("landing", nodeRepository.countLandingNodes());
        stats.put("active", nodeRepository.countActiveNodes());
        stats.put("disabled", nodeRepository.countDisabledNodes());
        
        return stats;
    }
    
    // 检查端口是否可用
    public boolean isPortAvailable(Long serverId, Integer port, Long nodeId) {
        if (nodeId == null) {
            return !nodeRepository.existsByServerIdAndPort(serverId, port);
        } else {
            return !nodeRepository.existsByServerIdAndPortAndIdNot(serverId, port, nodeId);
        }
    }

    @Transactional
    public Node saveNode(Node node) {
        // 设置默认值（如果未提供）
        if (node.getDeployed() == null) {
            node.setDeployed(0);
        }
        
        // 确保服务器存在
        if (node.getServerId() != null && !serverRepository.existsById(node.getServerId())) {
            throw new EntityNotFoundException("Server with ID " + node.getServerId() + " not found");
        }
        
        // 检查端口是否已被使用
        if (node.getServerId() != null && node.getPort() != null && 
                !isPortAvailable(node.getServerId(), node.getPort(), node.getId())) {
            throw new IllegalArgumentException("Port " + node.getPort() + " is already in use on this server");
        }
        
        // 处理JSON配置
        try {
            if (node.getInbound() == null) {
                node.setInbound(null);
            } else if (!(node.getInbound() instanceof String)) {
                node.setInbound(objectMapper.writeValueAsString(node.getInbound()));
            }
            
            if (node.getRule() == null) {
                node.setRule(null);
            } else if (!(node.getRule() instanceof String)) {
                node.setRule(objectMapper.writeValueAsString(node.getRule()));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting config to JSON: " + e.getMessage(), e);
        }
        
        return nodeRepository.save(node);
    }

    @Transactional
    public Node updateNode(Node node) {
        Node existingNode = nodeRepository.findById(node.getId())
                .orElseThrow(() -> new EntityNotFoundException("Node not found"));

        // 检查端口是否已被使用
        if (node.getServerId() != null && node.getPort() != null &&
                !isPortAvailable(node.getServerId(), node.getPort(), node.getId())) {
            throw new IllegalArgumentException("Port " + node.getPort() + " is already in use on this server");
        }

        // 处理JSON配置
        try {
            if (node.getInbound() == null) {
                node.setInbound(null);
            } else if (!(node.getInbound() instanceof String)) {
                node.setInbound(objectMapper.writeValueAsString(node.getInbound()));
            }

            if (node.getRule() == null) {
                node.setRule(null);
            } else if (!(node.getRule() instanceof String)) {
                node.setRule(objectMapper.writeValueAsString(node.getRule()));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting config to JSON: " + e.getMessage(), e);
        }

        // 检查节点是否有实质性变更
        boolean hasSubstantialChanges = hasSubstantialChanges(existingNode, node);

        // 使用工具方法复制非null属性
        copyNonNullProperties(node, existingNode);
        
        // 特殊处理 outId 字段，确保 null 值也能被更新
        existingNode.setOutId(node.getOutId());

        // 如果有实质性变更，将状态设置为"未部署"
        if (hasSubstantialChanges) {
            existingNode.setDeployed(0); // 设置为"未部署"
        }

        Node updatedNode = nodeRepository.save(existingNode);
        Server server = serverRepository.findById(updatedNode.getServerId()).orElse(null);
        updatedNode.setServer(server);
        return updatedNode;
    }

    /**
     * 检查节点是否有实质性变更（影响部署的变更）
     */
    private boolean hasSubstantialChanges(Node oldNode, Node newNode) {
        // 检查端口变更
        if (newNode.getPort() != null && !newNode.getPort().equals(oldNode.getPort())) {
            return true;
        }

        // 检查类型变更
        if (newNode.getType() != null && !newNode.getType().equals(oldNode.getType())) {
            return true;
        }

        // 检查服务器变更
        if (newNode.getServerId() != null && !newNode.getServerId().equals(oldNode.getServerId())) {
            return true;
        }

        // 检查配置变更
        if ((newNode.getInbound() != null && !newNode.getInbound().equals(oldNode.getInbound())) ||
                (newNode.getRule() != null && !newNode.getRule().equals(oldNode.getRule()))) {
            return true;
        }
        
        // 特殊处理 outId 变更，包括从有值变为 null 的情况
        if (newNode.getOutId() == null && oldNode.getOutId() != null) {
            return true;
        }
        if (newNode.getOutId() != null && !newNode.getOutId().equals(oldNode.getOutId())) {
            return true;
        }

        // 如果级别变更（会影响访问权限）
        if (newNode.getLevel() != null && !newNode.getLevel().equals(oldNode.getLevel())) {
            return true;
        }

        // 检查是否存在tag变更
        if (newNode.getTags() != null && !newNode.getTags().equals(oldNode.getTags())) {
            return true;
        }

        // 其他可能影响部署的字段...
        if (newNode.getDisabled() != null && !newNode.getDisabled().equals(oldNode.getDisabled())) {
            return true;
        }

        return false;
    }

    // 工具方法：复制非null属性
    private void copyNonNullProperties(Object src, Object target) {
        BeanUtils.copyProperties(src, target, getNullPropertyNames(src));
    }

    // 获取对象中所有为null的属性名
    private String[] getNullPropertyNames(Object source) {
        final BeanWrapper src = new BeanWrapperImpl(source);
        PropertyDescriptor[] pds = src.getPropertyDescriptors();

        Set<String> nullNames = new HashSet<>();
        for (PropertyDescriptor pd : pds) {
            Object srcValue = src.getPropertyValue(pd.getName());
            if (srcValue == null) {
                nullNames.add(pd.getName());
            }
        }
        return nullNames.toArray(new String[0]);
    }

    @Transactional
    public void deleteNode(Long id) {
        nodeRepository.deleteById(id);
    }

    @Transactional
    public Node toggleNodeStatus(Long id, boolean disabled) {
        Optional<Node> optionalNode = nodeRepository.findById(id);
        if (optionalNode.isPresent()) {
            Node node = optionalNode.get();
            node.setDisabled(disabled ? 1 : 0);
            node.setDeployed(0);
            return nodeRepository.save(node);
        }
        return null;
    }
    
    // 获取节点类型选项
    public List<Map<String, Object>> getNodeTypeOptions() {
        return Arrays.stream(NodeType.values())
                .map(type -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("value", type.getValue());
                    option.put("label", type.getDescription());
                    return option;
                })
                .collect(Collectors.toList());
    }
    
    // 获取协议类型选项
    public List<Map<String, Object>> getProtocolTypeOptions() {
        return Arrays.stream(ProtocolType.values())
                .map(type -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("value", type.getValue());
                    option.put("label", type.getValue());
                    option.put("type", type.getType());
                    return option;
                })
                .collect(Collectors.toList());
    }
    
    // 获取可用端口
    public Integer getAvailablePort(Long serverId) {
        List<Node> nodes = nodeRepository.findByServerId(serverId);
        
        // 整理已使用的端口
        Set<Integer> usedPorts = nodes.stream()
                .map(Node::getPort)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        // 查找未使用的端口（从10000开始）
        int port = 10000;
        while (usedPorts.contains(port)) {
            port++;
        }
        
        return port;
    }
    
    // 生成默认配置模板
    public Map<String, Object> generateDefaultInbound(String protocol) {
        Map<String, Object> inbound = new HashMap<>();
        inbound.put("protocol", protocol);
        
        // 根据协议类型生成不同的默认配置
        switch (protocol) {
            case "VLESS":
                inbound.put("encryption", "none");
                inbound.put("uuid", UUID.randomUUID().toString());
                break;
            case "Shadowsocks":
                inbound.put("method", "aes-256-gcm");
                inbound.put("password", generateRandomPassword(16));
                break;
            case "Socks":
                inbound.put("username", "user");
                inbound.put("password", generateRandomPassword(8));
                break;
            case "Hysteria2":
                inbound.put("password", generateRandomPassword(16));
                inbound.put("up_mbps", 100);
                inbound.put("down_mbps", 100);
                break;
            case "ShadowTLS":
                inbound.put("version", 3);
                inbound.put("password", generateRandomPassword(16));
                break;
        }
        
        return inbound;
    }
    
    // 生成随机密码
    private String generateRandomPassword(int length) {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder password = new StringBuilder();
        Random random = new Random();
        
        for (int i = 0; i < length; i++) {
            password.append(characters.charAt(random.nextInt(characters.length())));
        }
        
        return password.toString();
    }
}