package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.dto.deployment.DeploymentPreload;
import com.fun90.airopscat.service.NodeGroupService;
import com.fun90.airopscat.service.SystemConfigService;
import com.fun90.airopscat.singbox.SingBoxConfigBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

@Slf4j
@ApplicationScoped
public class NodeDeploymentService {

    private final NodeRepository nodeRepository;
    private final TagRepository tagRepository;
    private final NodeGroupService nodeGroupService;
    private final SystemConfigService systemConfigService;
    private final DeploymentDataLoader dataLoader;
    private final CoreDeploymentExecutor deploymentExecutor;
    private final SingBoxConfigBuilder singBoxConfigBuilder;
    private final ExecutorService blockingTaskExecutor;

    @Inject
    public NodeDeploymentService(NodeRepository nodeRepository,
                                 TagRepository tagRepository,
                                 NodeGroupService nodeGroupService,
                                 SystemConfigService systemConfigService,
                                 DeploymentDataLoader dataLoader,
                                 CoreDeploymentExecutor deploymentExecutor,
                                 SingBoxConfigBuilder singBoxConfigBuilder,
                                 @Named("deploymentTaskExecutor") ExecutorService blockingTaskExecutor) {
        this.nodeRepository = nodeRepository;
        this.tagRepository = tagRepository;
        this.nodeGroupService = nodeGroupService;
        this.systemConfigService = systemConfigService;
        this.dataLoader = dataLoader;
        this.deploymentExecutor = deploymentExecutor;
        this.singBoxConfigBuilder = singBoxConfigBuilder;
        this.blockingTaskExecutor = blockingTaskExecutor;
    }

    @Transactional
    public List<DeploymentResult> deployByAccountIds(List<Long> accountIdList) {
        List<Node> nodes = tagRepository.findNodesByAccountIds(accountIdList);
        if (nodes.isEmpty()) {
            log.info("No nodes found for account ids {}", accountIdList);
            return Collections.emptyList();
        }
        return deployNodesForcibly(nodes);
    }

    @Transactional
    public List<DeploymentResult> deployNodes(List<Long> nodeIds) {
        try {
            List<Node> undeployedNodes = nodeGroupService.expandWithRelatedGroups(getUndeployedNodes(nodeIds));
            if (undeployedNodes.isEmpty()) {
                log.info("No undeployed nodes found");
                return Collections.emptyList();
            }
            return processNodesByServer(undeployedNodes);
        } catch (Exception e) {
            log.error("Deploy nodes failed", e);
            throw new RuntimeException("节点部署失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<DeploymentResult> deployNodesForcibly(List<Node> nodes) {
        try {
            return processNodesByServer(nodeGroupService.expandWithRelatedGroups(nodes));
        } catch (Exception e) {
            log.error("Force deploy nodes failed", e);
            throw new RuntimeException("节点部署失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<DeploymentResult> deployAssociationGroupByNodeIds(List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Node> groupNodes = nodeGroupService.expandWithRelatedGroups(nodeRepository.findByIdIn(nodeIds));
        if (groupNodes.isEmpty()) {
            groupNodes = nodeRepository.findByIdIn(nodeIds);
        }
        return deployNodesForcibly(groupNodes);
    }

    private List<Node> getUndeployedNodes(List<Long> nodeIds) {
        if (nodeIds != null && !nodeIds.isEmpty()) {
            return nodeRepository.findByDeployedAndIdIn(0, nodeIds);
        }
        return nodeRepository.findByDeployed(0);
    }

    private List<DeploymentResult> processNodesByServer(List<Node> nodes) {
        DeploymentPreload preload = dataLoader.load(nodes);
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        List<DeploymentServerContext> serverContexts = preload.serverContexts().values().stream()
                .filter(ctx -> !ctx.nodes().isEmpty())
                .filter(ctx -> ctx.server().getDisabled() != 1)
                .toList();
        if (serverContexts.isEmpty()) {
            return Collections.emptyList();
        }

        int maxParallelServers = getMaxParallelServers();
        int batchCount = calculateBatchCount(serverContexts.size(), maxParallelServers);

        List<DeploymentResult> results = new ArrayList<>();
        for (int startIndex = 0; startIndex < serverContexts.size(); startIndex += maxParallelServers) {
            int endIndex = Math.min(startIndex + maxParallelServers, serverContexts.size());
            List<DeploymentServerContext> batch = serverContexts.subList(startIndex, endIndex);
            int currentBatch = (startIndex / maxParallelServers) + 1;
            log.info("节点部署批次开始，总服务器数: {}, 当前批次: {}/{}, 批大小: {}",
                    serverContexts.size(), currentBatch, batchCount, batch.size());

            List<CompletableFuture<List<CoreDeploymentExecution>>> futures = batch.stream()
                    .map(ctx -> CompletableFuture.supplyAsync(
                            withContextClassLoader(contextClassLoader, () -> deploymentExecutor.executeForServer(ctx)),
                            blockingTaskExecutor))
                    .toList();

            for (CompletableFuture<List<CoreDeploymentExecution>> future : futures) {
                for (CoreDeploymentExecution execution : future.join()) {
                    results.addAll(deploymentExecutor.persist(execution));
                }
            }
        }
        log.info("节点部署批处理完成，总服务器数: {}, 最大并发服务器数: {}, 批次数: {}",
                serverContexts.size(), maxParallelServers, batchCount);
        return results;
    }

    private int getMaxParallelServers() {
        return Math.max(systemConfigService.getIntValue("airopscat.deployment.max-parallel-servers", 4), 1);
    }

    private int calculateBatchCount(int totalCount, int batchSize) {
        return totalCount == 0 ? 0 : (int) Math.ceil((double) totalCount / batchSize);
    }

    private <T> Supplier<T> withContextClassLoader(ClassLoader contextClassLoader, Supplier<T> supplier) {
        return () -> {
            Thread currentThread = Thread.currentThread();
            ClassLoader originalClassLoader = currentThread.getContextClassLoader();
            try {
                currentThread.setContextClassLoader(contextClassLoader);
                return supplier.get();
            } finally {
                currentThread.setContextClassLoader(originalClassLoader);
            }
        };
    }

    public Map<String, String> previewServerConfigs(Long serverId) {
        DeploymentServerContext ctx = dataLoader.loadForServer(serverId);
        Server server = ctx.server();
        if (server == null) {
            return Collections.emptyMap();
        }

        Map<String, String> configs = new LinkedHashMap<>();
        try {
            configs.put("sing-box", singBoxConfigBuilder.build(ctx, ctx.nodes()));
        } catch (UnsupportedOperationException e) {
            log.warn("Skip preview for unsupported sing-box config on server {}", serverId);
        }
        return configs;
    }

}
