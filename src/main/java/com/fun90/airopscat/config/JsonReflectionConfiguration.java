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
    com.fun90.airopscat.model.enums.CoreType.class,
    com.fun90.airopscat.model.enums.CoreOperation.class,
    com.fun90.airopscat.model.enums.ServerAuthType.class,
    com.fun90.airopscat.model.enums.PaymentMethod.class,
    com.fun90.airopscat.model.enums.TransactionType.class,
    com.fun90.airopscat.model.enums.RouteRuleType.class,

    // Other DTO Classes
    ApiResponseDto.class,
    BarkNotificationDto.class,

    // Account and User Related DTOs
    com.fun90.airopscat.model.dto.AccountDto.class,
    com.fun90.airopscat.model.dto.AccountOnlineIpDto.class,
    com.fun90.airopscat.model.dto.AccountRequest.class,
    com.fun90.airopscat.model.dto.AccountTrafficStatsDto.class,
    com.fun90.airopscat.model.dto.UserDto.class,

    // Server and Node Related DTOs
    com.fun90.airopscat.model.dto.ServerDto.class,
    com.fun90.airopscat.model.dto.ServerConfigDto.class,
    com.fun90.airopscat.model.dto.ServerConfigRequest.class,
    com.fun90.airopscat.model.dto.NodeDto.class,
    com.fun90.airopscat.model.dto.NodeRequest.class,
    com.fun90.airopscat.model.dto.NodeCoreSwitchRequest.class,
    com.fun90.airopscat.model.dto.NodeCoreSwitchResponse.class,
    com.fun90.airopscat.model.dto.DomainDto.class,

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

    // Entity Classes (for direct JSON serialization)
    com.fun90.airopscat.model.entity.User.class,
    com.fun90.airopscat.model.entity.Account.class,
    com.fun90.airopscat.model.entity.AccountOnlineIp.class,
    com.fun90.airopscat.model.entity.AccountTrafficStats.class,
    com.fun90.airopscat.model.entity.Server.class,
    com.fun90.airopscat.model.entity.ServerConfig.class,
    com.fun90.airopscat.model.entity.Node.class,
    com.fun90.airopscat.model.entity.Domain.class,
    com.fun90.airopscat.model.entity.Tag.class,
    com.fun90.airopscat.model.entity.Transaction.class,
    com.fun90.airopscat.model.entity.RouteRule.class,

    // Time-related classes (Java standard classes are automatically registered)
    java.time.LocalDateTime.class,
    java.time.LocalDate.class,
    java.time.LocalTime.class
})
public class JsonReflectionConfiguration {
    // This class is empty, it exists only to hold the @RegisterForReflection annotation
}
