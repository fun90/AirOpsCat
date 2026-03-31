package com.fun90.airopscat.scheduler;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.TagRepository;
import com.fun90.airopscat.service.BarkService;
import com.fun90.airopscat.service.deployment.NodeDeploymentService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class AccountExpirationTask {

    @Inject
    AccountRepository accountRepository;

    @Inject
    TagRepository tagRepository;

    @Inject
    NodeDeploymentService nodeDeploymentService;

    @Inject
    BarkService barkService;

    @Scheduled(cron = "{airopscat.server.monitor.cron:0 0 5 * * ?}", timeZone = "Asia/Shanghai")
    @Transactional
    public void checkExpiredAccountsAndRedeployNodes() {
        log.info("开始执行定时任务：检查过期账号并重新部署节点");

        try {
            List<Account> expiredAccounts = accountRepository.findExpiredButNotDisabledAccounts(LocalDateTime.now());
            if (expiredAccounts.isEmpty()) {
                log.info("没有找到未禁用但已过期的账号，任务结束");
                return;
            }

            log.info("找到 {} 个未禁用但已过期的账号", expiredAccounts.size());

            List<Node> nodes = tagRepository.findNodesByAccountIds(
                    expiredAccounts.stream().map(Account::getId).distinct().toList()
            );
            if (nodes.isEmpty()) {
                log.info("过期账号没有关联的节点，任务结束");
                return;
            }

            log.info("需要重新部署的节点数量: {}", nodes.size());

            List<DeploymentResult> deploymentResults = nodeDeploymentService.deployNodesForcibly(nodes);

            try {
                accountRepository.disableExpiredAccounts(
                        expiredAccounts.stream().map(Account::getId).collect(Collectors.toList()),
                        LocalDateTime.now()
                );
            } catch (Exception e) {
                log.error("批量禁用过期账号时发生错误: {}", e.getMessage(), e);
            }

            long successCount = deploymentResults.stream()
                    .mapToLong(result -> result.isSuccess() ? 1 : 0)
                    .sum();
            long failureCount = deploymentResults.size() - successCount;

            log.info("节点重新部署完成 - 成功: {}, 失败: {}", successCount, failureCount);

            if (failureCount > 0) {
                deploymentResults.stream()
                        .filter(result -> !result.isSuccess())
                        .forEach(result -> log.error("节点 {} 重新部署失败: {}", result.getNodeId(), result.getMessage()));
            }

            barkService.sendInfoNotification("AirOpsCat 定时任务执行情况", "成功: " + successCount + " 失败: " + failureCount);
        } catch (Exception e) {
            log.error("执行定时任务时发生错误", e);
            barkService.sendErrorNotification("AirOpsCat 定时任务执行失败", "执行定时任务时发生错误: " + e.getMessage());
        }

        log.info("定时任务执行完成");
    }
}
