package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.deployment.DeploymentServerContext;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.dto.deployment.DeploymentPreload;
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

    private final NodeServerRepository nodeServerRepository;
    private final TagRepository tagRepository;
    private final SystemConfigService systemConfigService;
    private final DeploymentDataLoader dataLoader;
    private final CoreDeploymentExecutor deploymentExecutor;
    private final SingBoxConfigBuilder singBoxConfigBuilder;
    private final ExecutorService blockingTaskExecutor;

    @Inject
    public NodeDeploymentService(NodeServerRepository nodeServerRepository,
                                 TagRepository tagRepository,
                                 SystemConfigService systemConfigService,
                                 DeploymentDataLoader dataLoader,
                                 CoreDeploymentExecutor deploymentExecutor,
                                 SingBoxConfigBuilder singBoxConfigBuilder,
                                 @Named("deploymentTaskExecutor") ExecutorService blockingTaskExecutor) {
        this.nodeServerRepository = nodeServerRepository;
        this.tagRepository = tagRepository;
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
            List<NodeServer> pendingPlacements = nodeServerRepository.findByDeployedIn(List.of(
                    NodeDeploymentStatus.PENDING_DEPLOY.getValue(),
                    NodeDeploymentStatus.PENDING_DELETE.getValue()
            ), nodeIds);
            if (pendingPlacements.isEmpty()) {
                log.info("No pending deployment nodes found");
                return Collections.emptyList();
            }
            return processPlacementsByServer(pendingPlacements);
        } catch (Exception e) {
            log.error("Deploy nodes failed", e);
            throw new RuntimeException("节点部署失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<DeploymentResult> deployNodesForcibly(List<Node> nodes) {
        try {
            List<Long> nodeIds = nodes.stream().map(Node::getId).filter(Objects::nonNull).distinct().toList();
            if (nodeIds.isEmpty()) {
                return Collections.emptyList();
            }
            return processPlacementsByServer(nodeServerRepository.findByNodeIds(nodeIds));
        } catch (Exception e) {
            log.error("Force deploy nodes failed", e);
            throw new RuntimeException("节点部署失败: " + e.getMessage(), e);
        }
    }

    private List<DeploymentResult> processPlacementsByServer(List<NodeServer> placements) {
        DeploymentPreload preload = dataLoader.load(placements);
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        List<DeploymentServerContext> activeContexts = preload.serverContexts().values().stream()
                .filter(ctx -> !ctx.placements().isEmpty())
                .toList();
        List<DeploymentServerContext> serverContexts = activeContexts.stream()
                .filter(ctx -> ctx.server().getDisabled() != 1)
                .toList();
        // 已禁用的服务器不会被部署，需明确告知，否则调用方会误认为"无需部署"
        List<DeploymentResult> skipped = activeContexts.stream()
                .filter(ctx -> ctx.server().getDisabled() == 1)
                .flatMap(ctx -> ctx.placements().stream().map(placement -> new DeploymentResult(
                        placement.getNodeId(), ctx.server().getId(), false,
                        "服务器 " + ctx.server().getName() + " 已禁用，已跳过部署")))
                .toList();
        if (serverContexts.isEmpty()) {
            return skipped;
        }

        int maxParallelServers = getMaxParallelServers();
        int batchCount = calculateBatchCount(serverContexts.size(), maxParallelServers);

        List<DeploymentResult> results = new ArrayList<>(skipped);
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
            configs.put("sing-box", singBoxConfigBuilder.build(ctx, ctx.placements()));
        } catch (UnsupportedOperationException e) {
            log.warn("Skip preview for unsupported sing-box config on server {}", serverId);
        }
        return configs;
    }

}
