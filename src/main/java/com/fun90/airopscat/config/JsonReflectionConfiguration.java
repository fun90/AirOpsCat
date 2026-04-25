package com.fun90.airopscat.config;

import com.fun90.airopscat.model.dto.ApiResponseDto;
import com.fun90.airopscat.model.dto.BarkNotificationDto;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * JSON反序列化反射配置
 * 为Quarkus native image注册需要JSON反序列化的类
 */
@RegisterForReflection(targets = {
    // Enum Classes
    com.fun90.airopscat.model.enums.PeriodType.class,
    com.fun90.airopscat.model.enums.ProtocolType.class,
    com.fun90.airopscat.model.enums.NodeType.class,
    com.fun90.airopscat.model.enums.NodeBatchTagUpdateMode.class,
    com.fun90.airopscat.model.enums.CoreType.class,
    com.fun90.airopscat.model.enums.CoreOperation.class,
    com.fun90.airopscat.model.enums.ServerAuthType.class,
    com.fun90.airopscat.model.enums.PaymentMethod.class,
    com.fun90.airopscat.model.enums.TransactionType.class,
    com.fun90.airopscat.model.enums.RouteRuleType.class,
    com.fun90.airopscat.model.enums.DnsProviderType.class,
    com.fun90.airopscat.model.enums.DnsProviderConfigStatus.class,
    com.fun90.airopscat.model.enums.DnsProviderCheckStatus.class,
    com.fun90.airopscat.model.enums.DnsSyncStatus.class,
    com.fun90.airopscat.model.enums.DnsRecordStatus.class,

    // Other DTO Classes
    ApiResponseDto.class,
    BarkNotificationDto.class,
    com.fun90.airopscat.model.dto.SystemConfigItemDto.class,
    com.fun90.airopscat.model.dto.SystemConfigGroupDto.class,
    com.fun90.airopscat.model.dto.SystemConfigUpdateRequest.class,
    com.fun90.airopscat.model.dto.BarkConfigTestRequest.class,
    com.fun90.airopscat.model.dto.ScheduledTaskDto.class,
    com.fun90.airopscat.model.dto.singbox.SingBoxConnectionsResponse.class,
    com.fun90.airopscat.model.dto.singbox.SingBoxConnectionSnapshot.class,
    com.fun90.airopscat.model.dto.singbox.SingBoxConnectionMetadata.class,

    // Account and User Related DTOs
    com.fun90.airopscat.model.dto.AccountDto.class,
    com.fun90.airopscat.model.dto.AccountOnlineIpDto.class,
    com.fun90.airopscat.model.dto.AccountRequest.class,
    com.fun90.airopscat.model.dto.AccountTrafficStatsDto.class,
    com.fun90.airopscat.model.dto.UserDto.class,

    // Server and Node Related DTOs
    com.fun90.airopscat.model.dto.ServerDto.class,
    com.fun90.airopscat.model.dto.ServerHostDto.class,
    com.fun90.airopscat.model.dto.ServerMonitorPointDto.class,
    com.fun90.airopscat.model.dto.ServerMonitorSummaryDto.class,
    com.fun90.airopscat.model.dto.ServerMonitorChartDto.class,
    com.fun90.airopscat.model.dto.ServerMonitorTrafficCalibrationDto.class,
    com.fun90.airopscat.model.dto.ServerConfigDto.class,
    com.fun90.airopscat.model.dto.ServerConfigRequest.class,
    com.fun90.airopscat.model.dto.NodeDto.class,
    com.fun90.airopscat.model.dto.NodeRequest.class,
    com.fun90.airopscat.model.dto.NodeBatchTagUpdateRequest.class,
    com.fun90.airopscat.model.dto.NodeDeploymentRestoreRequest.class,
    com.fun90.airopscat.model.dto.NodeDeploymentVersionDto.class,
    com.fun90.airopscat.model.dto.NodeDeploymentVersionDetailDto.class,
    com.fun90.airopscat.model.dto.NodeDeploymentVersionSnapshotDto.class,
    com.fun90.airopscat.model.dto.DomainDto.class,
    com.fun90.airopscat.model.dto.DnsProviderConfigDto.class,
    com.fun90.airopscat.model.dto.DnsProviderConfigRequest.class,
    com.fun90.airopscat.model.dto.DnsProviderTestResponse.class,
    com.fun90.airopscat.model.dto.DomainDnsProviderBindingDto.class,
    com.fun90.airopscat.model.dto.DomainDnsProviderBindingRequest.class,
    com.fun90.airopscat.model.dto.DomainDnsPullResponse.class,
    com.fun90.airopscat.model.dto.DomainDnsPushResponse.class,
    com.fun90.airopscat.model.dto.DnsProviderRecord.class,
    com.fun90.airopscat.model.dto.DnsBatchChangeItem.class,
    com.fun90.airopscat.model.dto.DnsBatchChangeRequest.class,
    com.fun90.airopscat.model.dto.DnsBatchChangeResponse.class,
    com.fun90.airopscat.model.dto.DomainDnsRecordDto.class,
    com.fun90.airopscat.model.dto.DomainDnsRecordRequest.class,
    com.fun90.airopscat.model.dto.DomainDnsRecordBatchRequest.class,
    com.fun90.airopscat.model.dto.DomainDnsRecordBatchItemRequest.class,

    // Operation Result DTOs
    com.fun90.airopscat.model.dto.CommandResult.class,
    com.fun90.airopscat.model.dto.CoreManagementResult.class,
    com.fun90.airopscat.model.dto.DeploymentResult.class,

    // Configuration DTOs
    com.fun90.airopscat.model.dto.SshConfig.class,
    com.fun90.airopscat.model.dto.SubscrptionDto.class,
    com.fun90.airopscat.model.dto.ClientRequest.class,

    // Other DTOs
    com.fun90.airopscat.model.dto.TagDto.class,
    com.fun90.airopscat.model.dto.TransactionDto.class,
    com.fun90.airopscat.model.dto.RouteRuleDto.class,
    com.fun90.airopscat.model.dto.RouteRuleRequest.class,
    com.fun90.airopscat.model.dto.DefaultConfigDto.class,
    com.fun90.airopscat.model.dto.BackupFileDto.class,
    com.fun90.airopscat.model.dto.install.ServerInstallExecuteRequest.class,
    com.fun90.airopscat.model.dto.install.InstallScriptDto.class,
    com.fun90.airopscat.model.dto.install.ServerInstallStepResultDto.class,

    // Qute Template VO Classes
    com.fun90.airopscat.model.vo.ConsoleMenuGroup.class,
    com.fun90.airopscat.model.vo.ConsoleMenuItem.class,
    com.fun90.airopscat.model.vo.ConsolePage.class,

    // Entity Classes (for direct JSON serialization)
    com.fun90.airopscat.model.entity.User.class,
    com.fun90.airopscat.model.entity.Account.class,
    com.fun90.airopscat.model.entity.AlertState.class,
    com.fun90.airopscat.model.entity.AccountOnlineIp.class,
    com.fun90.airopscat.model.entity.AccountTrafficStats.class,
    com.fun90.airopscat.model.entity.Server.class,
    com.fun90.airopscat.model.entity.ServerMonitorStats.class,
    com.fun90.airopscat.model.entity.ServerHost.class,
    com.fun90.airopscat.model.entity.ServerConfig.class,
    com.fun90.airopscat.model.entity.Node.class,
    com.fun90.airopscat.model.entity.NodeDeployment.class,
    com.fun90.airopscat.model.entity.NodeDeploymentHistory.class,
    com.fun90.airopscat.model.entity.Domain.class,
    com.fun90.airopscat.model.entity.DnsProviderConfig.class,
    com.fun90.airopscat.model.entity.DomainDnsRecord.class,
    com.fun90.airopscat.model.entity.Tag.class,
    com.fun90.airopscat.model.entity.Transaction.class,
    com.fun90.airopscat.model.entity.RouteRule.class,
    com.fun90.airopscat.model.entity.SystemConfig.class,

    // MySQL JDBC classes loaded reflectively in native mode
    com.mysql.cj.PerConnectionLRUFactory.class,

    // Time-related classes (Java standard classes are automatically registered)
    java.time.LocalDateTime.class,
    java.time.LocalDate.class,
    java.time.LocalTime.class
})
public class JsonReflectionConfiguration {
    // This class is empty, it exists only to hold the @RegisterForReflection annotation
}
