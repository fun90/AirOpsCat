package com.fun90.airopscat.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.repository.AccountRepository;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TagService tagService;

    @Mock
    private NodeDeploymentService nodeDeploymentService;

    @Mock
    private BarkService barkService;

    @InjectMocks
    private ScheduledTaskService scheduledTaskService;

    private Account expiredAccount;
    private Node testNode;
    private DeploymentResult successResult;
    private DeploymentResult failureResult;

    @BeforeEach
    void setUp() {
        // 创建测试数据
        expiredAccount = new Account();
        expiredAccount.setId(1L);
        expiredAccount.setDisabled(0);
        expiredAccount.setToDate(LocalDateTime.now().minusDays(1));

        testNode = new Node();
        testNode.setId(1L);
        testNode.setServerId(1L);

        successResult = new DeploymentResult();
        successResult.setNodeId(1L);
        successResult.setSuccess(true);
        successResult.setMessage("部署成功");

        failureResult = new DeploymentResult();
        failureResult.setNodeId(2L);
        failureResult.setSuccess(false);
        failureResult.setMessage("部署失败");
    }

    @Test
    void testCheckExpiredAccountsAndRedeployNodes_NoExpiredAccounts() {
        // 模拟没有过期账户
        when(accountRepository.findExpiredButNotDisabledAccounts(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        // 执行测试
        scheduledTaskService.checkExpiredAccountsAndRedeployNodes();

        // 验证调用
        verify(accountRepository).findExpiredButNotDisabledAccounts(any(LocalDateTime.class));
        verify(tagService, never()).getAvailableNodesByAccount(any());
        verify(nodeDeploymentService, never()).deployNodes(anyList());
        verify(barkService, never()).sendInfoNotification(anyString(), anyString());
        verify(barkService, never()).sendErrorNotification(anyString(), anyString());
    }

    @Test
    void testCheckExpiredAccountsAndRedeployNodes_WithExpiredAccounts() {
        // 模拟有过期账户
        when(accountRepository.findExpiredButNotDisabledAccounts(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(expiredAccount));

        // 模拟账户关联的节点
        when(tagService.getAvailableNodesByAccount(expiredAccount.getId()))
                .thenReturn(Arrays.asList(testNode));

        // 模拟部署结果
        when(nodeDeploymentService.deployNodes(anyList()))
                .thenReturn(Arrays.asList(successResult));

        // 执行测试
        scheduledTaskService.checkExpiredAccountsAndRedeployNodes();

        // 验证调用
        verify(accountRepository).findExpiredButNotDisabledAccounts(any(LocalDateTime.class));
        verify(tagService).getAvailableNodesByAccount(expiredAccount.getId());
        verify(nodeDeploymentService).deployNodes(Arrays.asList(testNode.getId()));
        verify(barkService).sendInfoNotification("AirOpsCat 定时任务执行情况", "成功: 1 失败: 0");
    }

    @Test
    void testCheckExpiredAccountsAndRedeployNodes_WithMixedResults() {
        // 模拟有过期账户
        when(accountRepository.findExpiredButNotDisabledAccounts(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(expiredAccount));

        // 模拟账户关联的节点
        when(tagService.getAvailableNodesByAccount(expiredAccount.getId()))
                .thenReturn(Arrays.asList(testNode));

        // 模拟混合部署结果
        when(nodeDeploymentService.deployNodes(anyList()))
                .thenReturn(Arrays.asList(successResult, failureResult));

        // 执行测试
        scheduledTaskService.checkExpiredAccountsAndRedeployNodes();

        // 验证调用
        verify(accountRepository).findExpiredButNotDisabledAccounts(any(LocalDateTime.class));
        verify(tagService).getAvailableNodesByAccount(expiredAccount.getId());
        verify(nodeDeploymentService).deployNodes(Arrays.asList(testNode.getId()));
        verify(barkService).sendInfoNotification("AirOpsCat 定时任务执行情况", "成功: 1 失败: 1");
    }

    @Test
    void testCheckExpiredAccountsAndRedeployNodes_ExceptionHandling() {
        // 模拟抛出异常
        when(accountRepository.findExpiredButNotDisabledAccounts(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("数据库连接失败"));

        // 执行测试 - 不应该抛出异常
        scheduledTaskService.checkExpiredAccountsAndRedeployNodes();

        // 验证调用
        verify(accountRepository).findExpiredButNotDisabledAccounts(any(LocalDateTime.class));
        verify(tagService, never()).getAvailableNodesByAccount(any());
        verify(nodeDeploymentService, never()).deployNodes(anyList());
        verify(barkService).sendErrorNotification("AirOpsCat 定时任务执行失败", "执行定时任务时发生错误: 数据库连接失败");
    }
} 