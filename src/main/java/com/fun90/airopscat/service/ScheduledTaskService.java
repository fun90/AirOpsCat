package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 定时任务服务
 * 负责执行各种定时任务，如检查过期账户、重新部署节点等
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTaskService {

    private final AccountRepository accountRepository;
    private final TagService tagService;
    private final NodeDeploymentService nodeDeploymentService;
    private final BarkService barkService;

    /**
     * 每天凌晨5点执行的任务
     * 检查未禁用但已过期的账户，并重新部署相关的节点
     */
    @Scheduled(cron = "0 0 5 * * ?")
    @Transactional
    public void checkExpiredAccountsAndRedeployNodes() {
        log.info("开始执行定时任务：检查过期账户并重新部署节点");
        
        try {
            // 1. 查询未禁用但已过期的账户
            List<Account> expiredAccounts = accountRepository.findExpiredButNotDisabledAccounts(LocalDateTime.now());
            
            if (expiredAccounts.isEmpty()) {
                log.info("没有找到未禁用但已过期的账户，任务结束");
                return;
            }
            
            log.info("找到 {} 个未禁用但已过期的账户", expiredAccounts.size());
            
            // 2. 获取这些账户关联的所有节点ID
            Set<Node> nodes = expiredAccounts.stream()
                    .flatMap(account -> {
                        try {
                            return tagService.getAvailableNodesByAccount(account.getId()).stream();
                        } catch (Exception e) {
                            log.error("获取账户 {} 关联节点时发生错误: {}", account.getId(), e.getMessage());
                            return java.util.stream.Stream.<Node>empty();
                        }
                    })
                    .collect(Collectors.toSet());
            
            if (nodes.isEmpty()) {
                log.info("过期账户没有关联的节点，任务结束");
                return;
            }
            
            log.info("需要重新部署的节点数量: {}", nodes.size());
            
            // 3. 批量重新部署节点
            List<DeploymentResult> deploymentResults = nodeDeploymentService.deployNodesForcibly(
                    nodes.stream().toList()
            );

            // 4. 批量禁用过期账户
            accountRepository.disableExpiredAccounts(
                    expiredAccounts.stream().map(Account::getId).collect(Collectors.toList()),
                    LocalDateTime.now()
            );
            
            // 5. 统计部署结果
            long successCount = deploymentResults.stream()
                    .mapToLong(result -> result.isSuccess() ? 1 : 0)
                    .sum();
            long failureCount = deploymentResults.size() - successCount;
            
            log.info("节点重新部署完成 - 成功: {}, 失败: {}", successCount, failureCount);
            
            // 6. 记录失败的部署结果
            if (failureCount > 0) {
                deploymentResults.stream()
                        .filter(result -> !result.isSuccess())
                        .forEach(result -> log.error("节点 {} 重新部署失败: {}", 
                                result.getNodeId(), result.getMessage()));
            }
            barkService.sendInfoNotification("AirOpsCat 定时任务执行情况", "成功: " + successCount + " 失败: " + failureCount);
            
        } catch (Exception e) {
            log.error("执行定时任务时发生错误", e);
            barkService.sendErrorNotification("AirOpsCat 定时任务执行失败", "执行定时任务时发生错误: " + e.getMessage());
        }
        
        log.info("定时任务执行完成");
    }
} 