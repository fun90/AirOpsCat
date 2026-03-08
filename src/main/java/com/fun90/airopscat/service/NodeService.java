package com.fun90.airopscat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.DefaultConfigDto;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.setting.Sniffing;
import com.fun90.airopscat.model.dto.xray.setting.StreamSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.ShadowsocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.SocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.stream.Certificate;
import com.fun90.airopscat.model.dto.xray.setting.stream.RealitySettings;
import com.fun90.airopscat.model.dto.xray.setting.stream.TlsSettings;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.model.enums.ProtocolType;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.util.NativeRandomUtils;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class NodeService {

    private static final Logger log = LoggerFactory.getLogger(NodeService.class);
    @Inject
    NodeRepository nodeRepository;
    
    @Inject
    ServerRepository serverRepository;

    @Inject
    TagRepository tagRepository;
    
    @Inject
    ObjectMapper objectMapper;

    private static final String[] DESTINATIONS = {"www.apple.com", "www.icloud.com", "www.amazon.com"};

    /**
     * Generate X25519 key pair using xray command
     * @return array containing [privateKey, publicKey]
     */
    private String[] generateX25519Keys() {
        try {
            ProcessBuilder pb = new ProcessBuilder("xray", "x25519");
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            List<String> output = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
            
            process.waitFor();
            reader.close();
            
            // Expected output format:
            // Private key: xxx
            // Public key: xxx
            String privateKey = "";
            String publicKey = "";
            
            for (String outputLine : output) {
                if (outputLine.startsWith("PrivateKey:")) {
                    privateKey = outputLine.substring("PrivateKey:".length()).trim();
                } else if (outputLine.startsWith("Password:")) {
                    publicKey = outputLine.substring("Password:".length()).trim();
                }
            }
            
            return new String[]{privateKey, publicKey};
        } catch (IOException | InterruptedException e) {
            log.error("Failed to generate X25519 keys", e);
            // Fallback to hardcoded keys if xray command fails
            return new String[]{"ABR3X0eLYM_6CRHTFepn7GrpSHEFCYqzGFaZ6Uj1L0E", "_bhnIqIPO2m2ov5JY3BTroTVPpZk40Xbf6WLlRCxASw"};
        }
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Node> getNodePage(String search, Long serverId, Integer type, Boolean disabled) {
        // Build query string
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        
        // Search in name, remark, or server properties
        if (search != null && !search.trim().isEmpty()) {
            String searchLike = "%" + search.toLowerCase() + "%";
            query.append(" and (lower(name) like :search or lower(remark) like :search")
                 .append(" or serverId in (select id from Server where lower(ip) like :search or lower(host) like :search)");
            params.put("search", searchLike);

            List<Long> tagNodeIds = tagRepository.findNodeIdsByTagNameLike(searchLike);
            if (!tagNodeIds.isEmpty()) {
                query.append(" or id in :tagNodeIds");
                params.put("tagNodeIds", tagNodeIds);
            }
            query.append(")");
        }

        // Filter by serverId
        if (serverId != null) {
            query.append(" and serverId = :serverId");
            params.put("serverId", serverId);
        }

        // Filter by type
        if (type != null) {
            query.append(" and type = :type");
            params.put("type", type);
        }

        // Filter by disabled status
        if (disabled != null) {
            query.append(" and disabled = :disabled");
            params.put("disabled", disabled ? 1 : 0);
        }

        return nodeRepository.find(query.toString(), Sort.by("createTime").descending(), params);
    }

    public Node getNodeById(Long id) {
        return nodeRepository.findById(id);
    }

    public List<Node> getNodeByType(NodeType nodeType) {
        return nodeRepository.findByType(nodeType.getValue());
    }

    public List<Node> getNodesByServer(Long serverId) {
        return nodeRepository.findByServerId(serverId);
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

    // 检查备用服务器端口是否可用
    public boolean isBackupPortAvailable(Long backupServerId, Integer port, Long nodeId) {
        if (backupServerId == null) {
            return true; // 如果没有备用服务器，则认为可用
        }
        
        if (nodeId == null) {
            // 检查是否有其他节点在该备用服务器上使用了相同端口
            return !nodeRepository.existsByBackupServerIdAndPort(backupServerId, port);
        } else {
            // 检查是否有其他节点（除了当前节点）在该备用服务器上使用了相同端口
            return !nodeRepository.existsByBackupServerIdAndPortAndIdNot(backupServerId, port, nodeId);
        }
    }

    // 综合检查节点端口可用性（包括主服务器和备用服务器）- 使用单一SQL查询
    public boolean isNodePortsAvailable(Long serverId, Long backupServerId, Integer port, Long nodeId) {
        // 如果主服务器和备用服务器是同一个，直接返回false
        if (backupServerId != null && serverId.equals(backupServerId)) {
            return false;
        }
        
        // 使用单一SQL查询检查端口冲突
        return !nodeRepository.existsPortConflict(serverId, backupServerId, port, nodeId);
    }

    // 验证节点的服务器和端口
    private void validateNodeServersAndPorts(Node node) {
        // 确保主服务器存在
        if (node.getServerId() != null && serverRepository.findById(node.getServerId()) == null) {
            throw new EntityNotFoundException("Server with ID " + node.getServerId() + " not found");
        }
        
        // 确保备用服务器存在
        if (node.getBackupServerId() != null && serverRepository.findById(node.getBackupServerId()) == null) {
            throw new EntityNotFoundException("Backup server with ID " + node.getBackupServerId() + " not found");
        }
        
        // 检查端口是否已被使用（包括主服务器和备用服务器）
        if (node.getServerId() != null && node.getPort() != null) {
            if (!isNodePortsAvailable(node.getServerId(), node.getBackupServerId(), node.getPort(), node.getId())) {
                if (node.getBackupServerId() != null && node.getServerId().equals(node.getBackupServerId())) {
                    throw new IllegalArgumentException("Main server and backup server cannot be the same");
                } else if (!isPortAvailable(node.getServerId(), node.getPort(), node.getId())) {
                    throw new IllegalArgumentException("Port " + node.getPort() + " is already in use on the main server");
                } else if (!isBackupPortAvailable(node.getBackupServerId(), node.getPort(), node.getId())) {
                    throw new IllegalArgumentException("Port " + node.getPort() + " is already in use on the backup server");
                }
            }
        }
    }

    @Transactional
    public Node saveNode(Node node) {
        // 设置默认值（如果未提供）
        if (node.getDeployed() == null) {
            node.setDeployed(0);
        }
        
        // 验证服务器和端口
        validateNodeServersAndPorts(node);
        
        nodeRepository.persist(node);
        return node;
    }

    @Transactional
    public Node updateNode(Node node, Set<Tag> tagSet) {
        Node existingNode = nodeRepository.findById(node.getId());
        if (existingNode == null) {
            throw new EntityNotFoundException("Node not found");
        }

        // 验证服务器和端口
        validateNodeServersAndPorts(node);

        // 检查节点是否有实质性变更
        boolean hasSubstantialChanges = hasSubstantialChanges(existingNode, node, tagSet);

        // 使用工具方法复制非null属性
        copyNonNullProperties(node, existingNode);
        
        // 特殊处理 outId 字段，确保 null 值也能被更新
        existingNode.setOutId(node.getOutId());

        // 如果有实质性变更，将状态设置为"未部署"
        if (hasSubstantialChanges) {
            existingNode.setDeployed(0); // 设置为"未部署"
        }

        // No need to call save/persist for updates in Panache
        // 手动加载关联的Server对象，避免lazy loading问题
        if (existingNode.getServerId() != null) {
            Server server = serverRepository.findById(existingNode.getServerId());
            existingNode.setServer(server);
        }
        
        // 手动加载关联的BackupServer对象，避免lazy loading问题
        if (existingNode.getBackupServerId() != null) {
            Server backupServer = serverRepository.findById(existingNode.getBackupServerId());
            existingNode.setBackupServer(backupServer);
        }
        
        // 手动加载关联的OutNode对象，避免lazy loading问题
        if (existingNode.getOutId() != null) {
            Node outNode = nodeRepository.findById(existingNode.getOutId());
            existingNode.setOutNode(outNode);
        }
        
        return existingNode;
    }

    /**
     * 检查节点是否有实质性变更（影响部署的变更）
     */
    private boolean hasSubstantialChanges(Node oldNode, Node newNode, Set<Tag> newTagSet) {
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
        
        // 检查备用服务器变更
        if (newNode.getBackupServerId() == null && oldNode.getBackupServerId() != null) {
            return true;
        }
        if (newNode.getBackupServerId() != null && !newNode.getBackupServerId().equals(oldNode.getBackupServerId())) {
            return true;
        }

        // 检查配置变更
        if (newNode.getInbound() != null && !newNode.getInbound().equals(oldNode.getInbound())) {
            return true;
        }
        if (newNode.getRule() != null && !newNode.getRule().equals(oldNode.getRule())) {
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
        if (newNode.getTags() != null && !newTagSet.equals(oldNode.getTags())) {
            return true;
        }

        // 其他可能影响部署的字段...
        return newNode.getDisabled() != null && !newNode.getDisabled().equals(oldNode.getDisabled());
    }

    // 工具方法：手动复制非null属性（替代Spring BeanUtils）
    private void copyNonNullProperties(Node src, Node target) {
        if (src.getName() != null) target.setName(src.getName());
        if (src.getRemark() != null) target.setRemark(src.getRemark());
        if (src.getServerId() != null) target.setServerId(src.getServerId());
        if (src.getType() != null) target.setType(src.getType());
        if (src.getPort() != null) target.setPort(src.getPort());
        if (src.getProtocol() != null) target.setProtocol(src.getProtocol());
        if (src.getInbound() != null) target.setInbound(src.getInbound());
        if (src.getRule() != null) target.setRule(src.getRule());
        if (src.getLevel() != null) target.setLevel(src.getLevel());
        if (src.getTags() != null) target.setTags(src.getTags());
        if (src.getDisabled() != null) target.setDisabled(src.getDisabled());
        if (src.getDeployed() != null) target.setDeployed(src.getDeployed());
        // 特殊处理：outId 可能为 null，需要显式设置
        target.setOutId(src.getOutId());
        // 特殊处理：backupServerId 可能为 null，需要显式设置
        target.setBackupServerId(src.getBackupServerId());
    }

    @Transactional
    public void deleteNode(Long id) {
        nodeRepository.deleteById(id);
    }

    @Transactional
    public Node toggleNodeStatus(Long id, boolean disabled) {
        Node node = nodeRepository.findById(id);
        if (node != null) {
            node.setDisabled(disabled ? 1 : 0);
            node.setDeployed(0);
            // No need to call save/persist for updates in Panache
            return node;
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
                    option.put("label", type.getLabel());
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
    public DefaultConfigDto generateDefaultInbound(String protocol) {
        log.info("generateDefaultInbound start");
        DefaultConfigDto dto = new DefaultConfigDto();
        dto.setProtocol(protocol);
        InboundConfig inbound = new InboundConfig();
        dto.setConfig(inbound);
        log.info("generateDefaultInbound 1: {}", dto);
        // 根据协议类型生成不同的默认配置
        if ("vless".equalsIgnoreCase(protocol)) {
            log.info("generateDefaultInbound vless: {}", dto);
            inbound.setProtocol("vless");

            VlessInboundSetting setting = new VlessInboundSetting();
            setting.setClients(Collections.emptyList());
            setting.setDecryption("none");
            VlessInboundSetting.VlessFallback fallback0 = new VlessInboundSetting.VlessFallback();
            fallback0.setDest("7001");
            fallback0.setXver(1);
            VlessInboundSetting.VlessFallback fallback1 = new VlessInboundSetting.VlessFallback();
            fallback1.setAlpn("h2");
            fallback1.setDest("7002");
            fallback1.setXver(1);
            setting.setFallbacks(Stream.of(fallback0, fallback1).collect(Collectors.toList()));
            inbound.setSettings(setting);

            StreamSetting streamSetting = new StreamSetting();
            streamSetting.setNetwork("tcp");
            streamSetting.setSecurity("tls");

            TlsSettings tlsSettings = new TlsSettings();
            tlsSettings.setRejectUnknownSni(true);
            tlsSettings.setMinVersion("1.2");
            Certificate certificate = new Certificate();
            certificate.setOcspStapling(3600);
            certificate.setCertificateFile("/usr/local/etc/certs/xray.crt");
            certificate.setKeyFile("/usr/local/etc/certs/xray.key");
            tlsSettings.setCertificates(Collections.singletonList(certificate));
            streamSetting.setTlsSettings(tlsSettings);
            inbound.setStreamSettings(streamSetting);

            Sniffing sniffing = new Sniffing();
            sniffing.setEnabled(true);
            sniffing.setDestOverride(Stream.of("http", "tls").collect(Collectors.toList()));
            inbound.setSniffing(sniffing);
        } else if("vless-reality".equalsIgnoreCase(protocol)) {
            log.info("generateDefaultInbound vless-reality: {}", dto);
            inbound.setProtocol("vless");

            VlessInboundSetting setting = new VlessInboundSetting();
            setting.setClients(Collections.emptyList());
            setting.setDecryption("none");
            inbound.setSettings(setting);
            StreamSetting streamSetting = new StreamSetting();
            streamSetting.setNetwork("tcp");
            streamSetting.setSecurity("reality");
            RealitySettings realitySettings = new RealitySettings();
            realitySettings.setShow(false);

            // Randomly select destination and server name
            String selectedDest = NativeRandomUtils.randomChoice(DESTINATIONS);
            realitySettings.setDest(selectedDest + ":443");
            realitySettings.setServerNames(Collections.singletonList(selectedDest));

            // Generate X25519 keys
            String[] keys = generateX25519Keys();
            realitySettings.setPrivateKey(keys[0]);
            realitySettings.setPublicKey(keys[1]);

            // Generate random short IDs
            String shortId = NativeRandomUtils.generateRandomHexFast(16);
            realitySettings.setShortIds(List.of(shortId));
            streamSetting.setRealitySettings(realitySettings);
            inbound.setStreamSettings(streamSetting);

            Sniffing sniffing = new Sniffing();
            sniffing.setEnabled(true);
            sniffing.setDestOverride(Stream.of("http", "tls", "quic").collect(Collectors.toList()));
            sniffing.setRouteOnly(true);
            inbound.setSniffing(sniffing);
        } else if ("shadowsocks".equalsIgnoreCase(protocol)) {
            inbound.setProtocol("shadowsocks");

            ShadowsocksInboundSetting shadowsocksInboundSetting = new ShadowsocksInboundSetting();
            shadowsocksInboundSetting.setNetwork("tcp,udp");
            shadowsocksInboundSetting.setMethod("aes-256-gcm");
            shadowsocksInboundSetting.setPassword(generateRandomPassword(20));
            shadowsocksInboundSetting.setEmail(generateRandomPassword(8));
            shadowsocksInboundSetting.setLevel(0);
            inbound.setSettings(shadowsocksInboundSetting);
        } else if ("socks".equalsIgnoreCase(protocol)) {
            inbound.setProtocol("socks");

            SocksInboundSetting socksInboundSetting = new SocksInboundSetting();
            socksInboundSetting.setAuth("password");

            // Create SOCKS account with random user and password
            SocksInboundSetting.SocksAccount account = new SocksInboundSetting.SocksAccount();
            account.setUser(NativeRandomUtils.generateRandomHexFast(12));
            account.setPass(generateRandomPassword(20));

            socksInboundSetting.setAccounts(List.of(account));
            socksInboundSetting.setUdp(true);
            socksInboundSetting.setIp("127.0.0.1");

            inbound.setSettings(socksInboundSetting);
        } else if ("hysteria2".equalsIgnoreCase(protocol)) {
            inbound.setProtocol("hysteria2");
            // TODO: Implement Hysteria2 configuration generation
        } else if ("shadowtls".equalsIgnoreCase(protocol)) {
            inbound.setProtocol("shadowtls");
            // TODO: Implement ShadowTLS configuration generation
        }

        log.info("generateDefaultInbound 2: {}", dto);
        return dto;
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
