package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.model.dto.deployment.CoreDeploymentExecution;
import com.fun90.airopscat.model.dto.deployment.DeploymentPreload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class NodeDeploymentService {

    private final NodeRepository nodeRepository;
    private final TagRepository tagRepository;
    private final DeploymentDataLoader dataLoader;
    private final CoreDeploymentExecutor deploymentExecutor;

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
            List<Node> undeployedNodes = getUndeployedNodes(nodeIds);
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
            return processNodesByServer(nodes);
        } catch (Exception e) {
            log.error("Force deploy nodes failed", e);
            throw new RuntimeException("节点部署失败: " + e.getMessage(), e);
        }
    }

    private List<Node> getUndeployedNodes(List<Long> nodeIds) {
        if (nodeIds != null && !nodeIds.isEmpty()) {
            return nodeRepository.findByDeployedAndIdIn(0, nodeIds);
        }
        return nodeRepository.findByDeployed(0);
    }

    private List<DeploymentResult> processNodesByServer(List<Node> nodes) {
        DeploymentPreload preload = dataLoader.load(nodes);

        List<CompletableFuture<List<CoreDeploymentExecution>>> futures = preload.serverContexts().values().stream()
                .filter(ctx -> !ctx.nodes().isEmpty())
                .filter(ctx -> ctx.server().getDisabled() != 1)
                .map(ctx -> CompletableFuture.supplyAsync(() -> deploymentExecutor.executeForServer(ctx)))
                .toList();

        List<DeploymentResult> results = new ArrayList<>();
        for (CompletableFuture<List<CoreDeploymentExecution>> future : futures) {
            for (CoreDeploymentExecution execution : future.join()) {
                results.addAll(deploymentExecutor.persist(execution));
            }
        }
        return results;
    }
}