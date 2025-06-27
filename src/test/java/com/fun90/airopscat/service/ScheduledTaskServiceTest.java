package com.fun90.airopscat.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fun90.airopscat.model.dto.DeploymentResult;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.XrayConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerConfig;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.ServerConfigRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.service.ssh.SshConnection;
import com.fun90.airopscat.service.ssh.SshConnectionService;
import com.fun90.airopscat.utils.JsonUtil;

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

    @Mock
    private ServerConfigRepository serverConfigRepository;

    @Mock
    private ServerRepository serverRepository;

    @Mock
    private SshConnectionService sshConnectionService;

    @Mock
    private SshConnection sshConnection;

    @Mock
    private AccountTrafficStatsService accountTrafficStatsService;

    @InjectMocks
    private ScheduledTaskService scheduledTaskService;

    private Account expiredAccount;
    private Node testNode;
    private DeploymentResult successResult;
    private DeploymentResult failureResult;
    private Server testServer;
    private ServerConfig testServerConfig;
    private XrayConfig testXrayConfig;
    private Account testAccount;

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

        // 设置测试服务器
        testServer = new Server();
        testServer.setId(1L);
        testServer.setIp("192.168.1.100");
        testServer.setSshPort(22);
        testServer.setUsername("root");
        testServer.setAuth("password");
        testServer.setAuthType("PASSWORD");

        // 设置测试账户
        testAccount = new Account();
        testAccount.setId(1L);
        testAccount.setUserId(1L);
        testAccount.setUuid("test-user@example.com");
        testAccount.setAccountNo("test001");

        // 设置测试Xray配置
        testXrayConfig = new XrayConfig();
        
        // 创建VLESS入站配置
        InboundConfig inboundConfig = new InboundConfig();
        inboundConfig.setProtocol("vless");
        inboundConfig.setPort(443);
        
        VlessInboundSetting vlessSettings = new VlessInboundSetting();
        VlessInboundSetting.VlessClient client = new VlessInboundSetting.VlessClient();
        client.setId("test-uuid");
        client.setEmail("test-user@example.com");
        client.setLevel(0);
        vlessSettings.setClients(Arrays.asList(client));
        
        inboundConfig.setSettings(vlessSettings);
        testXrayConfig.setInbounds(Arrays.asList(inboundConfig));

        // 设置测试服务器配置
        testServerConfig = new ServerConfig();
        testServerConfig.setId(1L);
        testServerConfig.setServerId(1L);
        testServerConfig.setConfigType("XRAY");
        testServerConfig.setConfig(JsonUtil.toJsonString(testXrayConfig));
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

    @Test
    void testCollectUserTrafficStats_WithValidConfig() throws Exception {
        // 准备测试数据
        when(serverConfigRepository.findByConfigType("XRAY"))
                .thenReturn(Arrays.asList(testServerConfig));
        when(serverRepository.findById(1L))
                .thenReturn(Optional.of(testServer));
        when(accountRepository.findByUuid("test-user@example.com"))
                .thenReturn(Optional.of(testAccount));
        when(sshConnectionService.createConnection(any()))
                .thenReturn(sshConnection);
        when(sshConnection.executeCommand(anyString()))
                .thenReturn(createMockCommandResult("{\"stat\":[{\"name\":\"user>>>test-user@example.com>>>traffic>>>uplink\",\"value\":1024}]}"))
                .thenReturn(createMockCommandResult("{\"stat\":[{\"name\":\"user>>>test-user@example.com>>>traffic>>>downlink\",\"value\":2048}]}"));

        // 执行测试
        scheduledTaskService.collectUserTrafficStats();

        // 验证调用
        verify(serverConfigRepository).findByConfigType("XRAY");
        verify(serverRepository).findById(1L);
        verify(accountRepository).findByUuid("test-user@example.com");
        verify(sshConnectionService).createConnection(any());
        verify(sshConnection, times(2)).executeCommand(anyString());
        verify(accountTrafficStatsService).saveStats(any());
    }

    @Test
    void testCollectUserTrafficStats_WithNoXrayConfig() {
        // 准备测试数据 - 没有Xray配置
        when(serverConfigRepository.findByConfigType("XRAY"))
                .thenReturn(Arrays.asList());

        // 执行测试
        scheduledTaskService.collectUserTrafficStats();

        // 验证调用
        verify(serverConfigRepository).findByConfigType("XRAY");
        verify(serverRepository, never()).findById(any());
        verify(accountRepository, never()).findByUuid(any());
    }

    @Test
    void testCollectUserTrafficStats_WithInvalidServer() {
        // 准备测试数据 - 服务器不存在
        when(serverConfigRepository.findByConfigType("XRAY"))
                .thenReturn(Arrays.asList(testServerConfig));
        when(serverRepository.findById(1L))
                .thenReturn(Optional.empty());

        // 执行测试
        scheduledTaskService.collectUserTrafficStats();

        // 验证调用
        verify(serverConfigRepository).findByConfigType("XRAY");
        verify(serverRepository).findById(1L);
        verify(accountRepository, never()).findByUuid(any());
    }

    @Test
    void testCollectUserTrafficStats_WithInvalidXrayConfig() {
        // 准备测试数据 - 无效的Xray配置
        testServerConfig.setConfig("invalid json");
        
        when(serverConfigRepository.findByConfigType("XRAY"))
                .thenReturn(Arrays.asList(testServerConfig));
        when(serverRepository.findById(1L))
                .thenReturn(Optional.of(testServer));

        // 执行测试
        scheduledTaskService.collectUserTrafficStats();

        // 验证调用
        verify(serverConfigRepository).findByConfigType("XRAY");
        verify(serverRepository).findById(1L);
        verify(accountRepository, never()).findByUuid(any());
    }

    @Test
    void testCollectUserTrafficStats_WithNoMatchingAccount() throws Exception {
        // 准备测试数据 - 没有匹配的账户
        when(serverConfigRepository.findByConfigType("XRAY"))
                .thenReturn(Arrays.asList(testServerConfig));
        when(serverRepository.findById(1L))
                .thenReturn(Optional.of(testServer));
        when(accountRepository.findByUuid("test-user@example.com"))
                .thenReturn(Optional.empty());
        when(sshConnectionService.createConnection(any()))
                .thenReturn(sshConnection);
        when(sshConnection.executeCommand(anyString()))
                .thenReturn(createMockCommandResult("{\"stat\":[{\"name\":\"user>>>test-user@example.com>>>traffic>>>uplink\",\"value\":1024}]}"))
                .thenReturn(createMockCommandResult("{\"stat\":[{\"name\":\"user>>>test-user@example.com>>>traffic>>>downlink\",\"value\":2048}]}"));

        // 执行测试
        scheduledTaskService.collectUserTrafficStats();

        // 验证调用
        verify(serverConfigRepository).findByConfigType("XRAY");
        verify(serverRepository).findById(1L);
        verify(accountRepository).findByUuid("test-user@example.com");
        verify(sshConnectionService).createConnection(any());
        verify(sshConnection, times(2)).executeCommand(anyString());
        verify(accountTrafficStatsService, never()).saveStats(any());
    }

    @Test
    void testCollectUserTrafficStats_WithSshException() throws Exception {
        // 准备测试数据 - SSH连接异常
        when(serverConfigRepository.findByConfigType("XRAY"))
                .thenReturn(Arrays.asList(testServerConfig));
        when(serverRepository.findById(1L))
                .thenReturn(Optional.of(testServer));
        when(sshConnectionService.createConnection(any()))
                .thenThrow(new RuntimeException("SSH connection failed"));

        // 执行测试
        scheduledTaskService.collectUserTrafficStats();

        // 验证调用
        verify(serverConfigRepository).findByConfigType("XRAY");
        verify(serverRepository).findById(1L);
        verify(sshConnectionService).createConnection(any());
        verify(accountRepository, never()).findByUuid(any());
        verify(accountTrafficStatsService, never()).saveStats(any());
    }

    @Test
    void testCollectUserTrafficStats_WithEmptyTrafficStats() throws Exception {
        // 准备测试数据 - 空的流量统计
        when(serverConfigRepository.findByConfigType("XRAY"))
                .thenReturn(Arrays.asList(testServerConfig));
        when(serverRepository.findById(1L))
                .thenReturn(Optional.of(testServer));
        when(accountRepository.findByUuid("test-user@example.com"))
                .thenReturn(Optional.of(testAccount));
        when(sshConnectionService.createConnection(any()))
                .thenReturn(sshConnection);
        when(sshConnection.executeCommand(anyString()))
                .thenReturn(createMockCommandResult("{\"stat\":[]}"))
                .thenReturn(createMockCommandResult("{\"stat\":[]}"));

        // 执行测试
        scheduledTaskService.collectUserTrafficStats();

        // 验证调用
        verify(serverConfigRepository).findByConfigType("XRAY");
        verify(serverRepository).findById(1L);
        verify(accountRepository).findByUuid("test-user@example.com");
        verify(sshConnectionService).createConnection(any());
        verify(sshConnection, times(2)).executeCommand(anyString());
        verify(accountTrafficStatsService).saveStats(any());
    }

    /**
     * 创建模拟的命令执行结果
     */
    private com.fun90.airopscat.model.dto.CommandResult createMockCommandResult(String output) {
        com.fun90.airopscat.model.dto.CommandResult result = new com.fun90.airopscat.model.dto.CommandResult();
        result.setExitStatus(0);
        result.setStdout(output);
        result.setStderr("");
        return result;
    }
} 