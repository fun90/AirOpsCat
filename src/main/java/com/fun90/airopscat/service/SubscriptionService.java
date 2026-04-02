package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.ApiResponseDto;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.dto.NodeDeploymentVersionSnapshotDto;
import com.fun90.airopscat.model.dto.SubscrptionDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeDeployment;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerHost;
import com.fun90.airopscat.model.enums.NodeType;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.AccountTrafficStatsRepository;
import com.fun90.airopscat.repository.NodeDeploymentRepository;
import com.fun90.airopscat.repository.ServerHostRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.util.ConfigFileReader;
import com.fun90.airopscat.util.JsonUtil;
import com.fun90.airopscat.util.NodeObfuscator;
import com.fun90.airopscat.util.TemplateUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class SubscriptionService {

    private final AccountRepository accountRepository;
    private final AccountTrafficStatsRepository accountTrafficRepository;
    private final NodeDeploymentRepository nodeDeploymentRepository;
    private final ServerRepository serverRepository;
    private final ServerHostRepository serverHostRepository;
    private final TagService tagService;
    private final TemplateUtil templateUtil;
    private final ConfigFileReader configFileReader;
    private final SystemConfigService systemConfigService;

    @Inject
    public SubscriptionService(
            AccountRepository accountRepository,
            AccountTrafficStatsRepository accountTrafficRepository,
            NodeDeploymentRepository nodeDeploymentRepository,
            ServerRepository serverRepository,
            ServerHostRepository serverHostRepository,
            TagService tagService,
            TemplateUtil templateUtil,
            ConfigFileReader configFileReader,
            SystemConfigService systemConfigService) {
        this.accountRepository = accountRepository;
        this.accountTrafficRepository = accountTrafficRepository;
        this.nodeDeploymentRepository = nodeDeploymentRepository;
        this.serverRepository = serverRepository;
        this.serverHostRepository = serverHostRepository;
        this.tagService = tagService;
        this.templateUtil = templateUtil;
        this.configFileReader = configFileReader;
        this.systemConfigService = systemConfigService;
    }

    /**
     * 获取规则内容
     */
    public String getRule(String appType, String ruleName) {
        if (appType == null || appType.trim().isEmpty()) {
            return "错误: 应用类型不能为空";
        }
        if (ruleName == null || ruleName.trim().isEmpty()) {
            return "错误: 规则名称不能为空";
        }

        String templateName = "rules/" + appType + "/" + ruleName;
        String content = getTemplateContent(templateName);
        return content;
    }

    public String getNodes(String authCode, String appType) {
        // 根据authCode查找账户
        Optional<Account> optionalAccount = accountRepository.findByAuthCode(authCode);
        if (optionalAccount.isEmpty()) {
            return "错误: 无效的认证码，账户不存在";
        }

        Account account = optionalAccount.get();

        // 检查账户状态
        if (!account.isActive()) {
            return "错误: 账户已被禁用，请联系管理员";
        }

        // 检查账户是否过期
        if (account.getToDate() != null && account.getToDate().isBefore(java.time.LocalDateTime.now())) {
            return "错误: 账户已过期，请续费后重试";
        }

        List<NodeDto> activeNodes = loadAvailableSubscriptionNodes(account.getId());
        if (activeNodes.isEmpty()) {
            return "错误: 当前账户没有可用的节点，请联系管理员";
        }

        // 节点混淆
        if (account.getNodeMultiple() != null) {
            activeNodes = NodeObfuscator.obfuscate(activeNodes, account.getNodeMultiple(), account.getNodePrefix());
        }

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("account", account);
        templateData.put("nodes", activeNodes);
        templateData.put("subscriptionUrl", getSubscriptionUrl());
        templateData.put("timestamp", System.currentTimeMillis());

        String templateName = "nodes/" + appType + ".html";
        String templateContent = getTemplateContent(templateName);

        String result = templateUtil.processStringTemplate(templateContent, templateData);
        // 清理多余的空行
        return result.replaceAll("(?m)^\\s*$[\n\r]+", "").trim();
    }

    /**
     * 生成订阅内容
     */
    public ApiResponseDto<SubscrptionDto> generateSubscription(String authCode, String osName, String appName, Map<String, String> params) {
        // 验证参数
        if (authCode == null || authCode.trim().isEmpty()) {
            return ApiResponseDto.error("认证码不能为空");
        }
        if (osName == null || osName.trim().isEmpty()) {
            return ApiResponseDto.error("操作系统名称不能为空");
        }
        if (appName == null || appName.trim().isEmpty()) {
            return ApiResponseDto.error("应用名称不能为空");
        }

        // 根据authCode查找账户
        Optional<Account> optionalAccount = accountRepository.findByAuthCode(authCode);
        if (optionalAccount.isEmpty()) {
            return ApiResponseDto.error("无效的认证码，账户不存在");
        }

        Account account = optionalAccount.get();

        // 检查账户状态
        if (!account.isActive()) {
            return ApiResponseDto.error("账户已被禁用，请联系管理员");
        }

        // 检查账户是否过期
        if (account.getToDate() != null && account.getToDate().isBefore(java.time.LocalDateTime.now())) {
            return ApiResponseDto.error("账户已过期，请续费后重试");
        }

        List<NodeDto> activeNodes = loadAvailableSubscriptionNodes(account.getId());
        if (activeNodes.isEmpty()) {
            return ApiResponseDto.error("当前账户没有可用的节点，请联系管理员");
        }

        // 节点混淆
        if (account.getNodeMultiple() != null) {
            activeNodes = NodeObfuscator.obfuscate(activeNodes, account.getNodeMultiple(), account.getNodePrefix());
        }

        // 根据应用类型生成配置
        String content = generateConfigByApp(account, activeNodes, osName, appName, params);
        if (content == null) {
            return ApiResponseDto.error("生成配置文件失败，不支持的应用类型: " + appName);
        }


        String fileName = account.getRemark();
        long bandwidth = account.getBandwidth() != null ? account.getBandwidth() : 500L;
        bandwidth = bandwidth * 1024L * 1024L * 1024L;
        LocalDateTime currentTime = LocalDateTime.now();
        List<AccountTrafficStats> trafficStatsList = accountTrafficRepository.findByAccountIdAndCurrentTime(account.getId(), currentTime);
        AccountTrafficStats accountTrafficStats = trafficStatsList.isEmpty() ? null : trafficStatsList.getFirst();
        long usedFlow = accountTrafficStats != null ? accountTrafficStats.getUploadBytes() + accountTrafficStats.getDownloadBytes() : 0L;
        SubscrptionDto subscriptionDto = new SubscrptionDto(fileName, content, account.getToDate(), usedFlow, bandwidth);
        return ApiResponseDto.success(subscriptionDto);
    }

    /**
     * 根据应用类型生成配置
     */
    private String generateConfigByApp(Account account, List<NodeDto> nodes, String osName, String appName, Map<String, String> params) {
        Map<String, Object> templateData = new HashMap<>();
        templateData.put("account", account);
        templateData.put("nodes", nodes);
        templateData.put("osName", osName);
        templateData.put("appName", appName);
        templateData.put("subscriptionUrl", getSubscriptionUrl());
        templateData.put("timestamp", System.currentTimeMillis());
        templateData.putAll(params);

        String templateName = getTemplateName(appName);
        String templateContent = getTemplateContent(templateName);
        return templateUtil.processStringTemplate(templateContent, templateData);
    }

    // 获取配置URL
    public String getConfigUrl(Account account, String osName, String appName) {
        if (account == null || account.getUuid() == null) {
            return null;
        }
        String url = getSubscriptionUrl() + "/config/" + account.getAuthCode() + "/" + osName + "/" + appName + "/";
        if ("shadowrocket".equalsIgnoreCase(appName)) {
            url += URLEncoder.encode(account.getRemark(), StandardCharsets.UTF_8) + "/";
        }
        return url;
    }

    private String getSubscriptionUrl() {
        return systemConfigService.getResolvedValue("airopscat.subscription.url");
    }

    /**
     * 获取模板名称
     */
    private String getTemplateName(String appName) {
        return appName + getSubscriptionFileSuffix(appName);
    }

    private String getSubscriptionFileSuffix(String appName) {
        if ("loon".equalsIgnoreCase(appName)) {
            return ".conf";
        } else if ("sing-box".equalsIgnoreCase(appName)) {
            return ".json";
        }
        return ".yaml";
    }

    /**
     * 获取模板内容
     * 优先从外部目录读取，如果不存在则从 classpath 读取
     */
    private String getTemplateContent(String templateName) {
        return configFileReader.readFileContent("config/subscription/" + templateName);
    }

    private List<NodeDto> loadAvailableSubscriptionNodes(Long accountId) {
        List<Node> availableNodes = tagService.getAvailableNodesByAccount(accountId);
        if (availableNodes.isEmpty()) {
            return List.of();
        }

        Map<Long, Node> availableNodeMap = availableNodes.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Node::getId, node -> node, (left, right) -> left, LinkedHashMap::new));
        List<NodeDeployment> deployments = nodeDeploymentRepository.findByNodeIds(new ArrayList<>(availableNodeMap.keySet()));
        if (deployments.isEmpty()) {
            return List.of();
        }

        Map<Long, NodeDeploymentVersionSnapshotDto> snapshotMap = new HashMap<>();
        Set<Long> serverIds = new LinkedHashSet<>();
        Set<Long> accessHostIds = new LinkedHashSet<>();
        for (NodeDeployment deployment : deployments) {
            NodeDeploymentVersionSnapshotDto snapshot = toDeploymentSnapshot(deployment);
            if (snapshot == null || snapshot.getNodeId() == null) {
                continue;
            }
            snapshotMap.put(snapshot.getNodeId(), snapshot);
            if (snapshot.getServerId() != null) {
                serverIds.add(snapshot.getServerId());
            }
            if (snapshot.getAccessHostId() != null) {
                accessHostIds.add(snapshot.getAccessHostId());
            }
        }

        Map<Long, Server> serverMap = serverRepository.findByIdIn(new ArrayList<>(serverIds)).stream()
                .collect(Collectors.toMap(Server::getId, server -> server));
        Map<Long, ServerHost> accessHostMap = accessHostIds.isEmpty()
                ? Collections.emptyMap()
                : serverHostRepository.list("id in ?1", new ArrayList<>(accessHostIds)).stream()
                        .collect(Collectors.toMap(ServerHost::getId, host -> host));

        List<NodeDto> nodes = new ArrayList<>();
        for (Node availableNode : availableNodeMap.values()) {
            NodeDeploymentVersionSnapshotDto snapshot = snapshotMap.get(availableNode.getId());
            if (snapshot == null) {
                continue;
            }

            NodeDto nodeDto = toSubscriptionNodeDto(
                    snapshot,
                    serverMap.get(snapshot.getServerId()),
                    accessHostMap.get(snapshot.getAccessHostId())
            );
            if (nodeDto.getDisabled() != null && nodeDto.getDisabled() == 1) {
                continue;
            }
            if (!Objects.equals(nodeDto.getType(), NodeType.PROXY.getValue())) {
                continue;
            }
            nodes.add(nodeDto);
        }
        return nodes;
    }

    private NodeDeploymentVersionSnapshotDto toDeploymentSnapshot(NodeDeployment deployment) {
        if (deployment == null || deployment.getSnapshotJson() == null || deployment.getSnapshotJson().isBlank()) {
            return null;
        }
        return JsonUtil.toObject(deployment.getSnapshotJson(), NodeDeploymentVersionSnapshotDto.class);
    }

    private NodeDto toSubscriptionNodeDto(NodeDeploymentVersionSnapshotDto snapshot, Server server, ServerHost accessHost) {
        NodeDto dto = new NodeDto();
        dto.setId(snapshot.getNodeId());
        dto.setServerId(snapshot.getServerId());
        dto.setAccessHostId(snapshot.getAccessHostId());
        dto.setNodeGroup(snapshot.getNodeGroup());
        dto.setPort(snapshot.getPort());
        dto.setProtocol(snapshot.getProtocol());
        dto.setCoreType(snapshot.getCoreType());
        dto.setType(snapshot.getType());
        dto.setTypeDescription(dto.getTypeDesc());
        dto.setInbound(snapshot.getInbound() != null ? new HashMap<>(snapshot.getInbound()) : new HashMap<>());
        dto.setOutId(snapshot.getOutId());
        dto.setRule(snapshot.getRule() != null ? new HashMap<>(snapshot.getRule()) : new HashMap<>());
        dto.setLevel(snapshot.getLevel());
        dto.setDeployed(1);
        dto.setDisabled(snapshot.getDisabled());
        dto.setName(snapshot.getName());
        dto.setNo(snapshot.getNo());
        dto.setRemark(snapshot.getRemark());
        dto.setAccessHost(accessHost != null ? accessHost.getHost() : null);

        if (server != null) {
            dto.setServerIp(server.getIp());
            dto.setServerHost(resolveEffectiveHost(accessHost, server));
        }
        return dto;
    }

    private String resolveEffectiveHost(ServerHost accessHost, Server server) {
        if (accessHost != null && accessHost.getHost() != null && !accessHost.getHost().isBlank()) {
            return accessHost.getHost();
        }
        if (server == null) {
            return null;
        }
        if (server.getHost() != null && !server.getHost().isBlank()) {
            return server.getHost();
        }
        return server.getIp();
    }
}
