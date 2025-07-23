package com.fun90.airopscat.config;

import com.fun90.airopscat.config.jackson.InboundConfigDeserializer;
import com.fun90.airopscat.config.jackson.OutboundConfigDeserializer;
import com.fun90.airopscat.model.dto.ApiResponseDto;
import com.fun90.airopscat.model.dto.BarkNotificationDto;
import com.fun90.airopscat.model.dto.xray.*;
import com.fun90.airopscat.model.dto.xray.policy.LevelPolicy;
import com.fun90.airopscat.model.dto.xray.policy.SystemPolicy;
import com.fun90.airopscat.model.dto.xray.routing.BalancerConfig;
import com.fun90.airopscat.model.dto.xray.routing.RoutingRule;
import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.OutboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.StreamSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.DokodemoDoorInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.ShadowsocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.SocksInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.outbound.*;
import com.fun90.airopscat.model.dto.xray.setting.stream.*;
import com.fun90.airopscat.model.enums.XrayProtocolType;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * JSON反序列化反射配置
 * 为Quarkus native image注册需要JSON反序列化的类
 */
@RegisterForReflection(targets = {
    // Core Xray Configuration Classes
    XrayConfig.class,
    ApiConfig.class,
    LogConfig.class,
    PolicyConfig.class,
    InboundConfig.class,
    OutboundConfig.class,
    RoutingConfig.class,
    
    // Policy Classes
    LevelPolicy.class,
    SystemPolicy.class,
    
    // Routing Classes
    BalancerConfig.class,
    RoutingRule.class,
    
    // Setting Classes
    InboundSetting.class,
    OutboundSetting.class,
    StreamSetting.class,
    
    // Inbound Setting Classes
    DokodemoDoorInboundSetting.class,
    ShadowsocksInboundSetting.class,
    ShadowsocksInboundSetting.ShadowsocksClient.class,
    SocksInboundSetting.class,
    SocksInboundSetting.SocksAccount.class,
    VlessInboundSetting.class,
    VlessInboundSetting.VlessClient.class,
    VlessInboundSetting.VlessFallback.class,
    
    // Outbound Setting Classes
    BlackholeOutboundSetting.class,
    BlackholeOutboundSetting.BlackholeResponse.class,
    FreedomOutboundSetting.class,
    ShadowsocksOutboundSetting.class,
    ShadowsocksOutboundSetting.ShadowsocksServer.class,
    SocksOutboundSetting.class,
    SocksOutboundSetting.SocksServer.class,
    SocksOutboundSetting.SocksUser.class,
    VlessOutboundSetting.class,
    VlessOutboundSetting.VlessServer.class,
    VlessOutboundSetting.VlessUser.class,
    
    // Stream Setting Classes
    GrpcSettings.class,
    HttpSettings.class,
    RealitySettings.class,
    TcpSettings.class,
    WebSocketSettings.class,
    
    // Enum Classes
    XrayProtocolType.class,
    com.fun90.airopscat.model.enums.PeriodType.class,
    com.fun90.airopscat.model.enums.ProtocolType.class,
    com.fun90.airopscat.model.enums.NodeType.class,
    com.fun90.airopscat.model.enums.CoreType.class,
    com.fun90.airopscat.model.enums.CoreOperation.class,
    com.fun90.airopscat.model.enums.ServerAuthType.class,
    com.fun90.airopscat.model.enums.PaymentMethod.class,
    com.fun90.airopscat.model.enums.TransactionType.class,
    
    // Jackson Deserializers
    InboundConfigDeserializer.class,
    OutboundConfigDeserializer.class,
    
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
    com.fun90.airopscat.model.dto.DomainDto.class,
    
    // Operation Result DTOs
    com.fun90.airopscat.model.dto.CommandResult.class,
    com.fun90.airopscat.model.dto.BatchCommandResult.class,
    com.fun90.airopscat.model.dto.CoreManagementResult.class,
    com.fun90.airopscat.model.dto.BatchCoreManagementResult.class,
    com.fun90.airopscat.model.dto.DeploymentResult.class,
    
    // Configuration DTOs
    com.fun90.airopscat.model.dto.SshConfig.class,
    com.fun90.airopscat.model.dto.SubscrptionDto.class,
    com.fun90.airopscat.model.dto.ClientRequest.class,
    com.fun90.airopscat.model.dto.LoginRequest.class,
    
    // Other DTOs
    com.fun90.airopscat.model.dto.TagDto.class,
    com.fun90.airopscat.model.dto.TransactionDto.class,
    
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

    // Time-related classes (Java standard classes are automatically registered)
    java.time.LocalDateTime.class,
    java.time.LocalDate.class,
    java.time.LocalTime.class
})
public class JsonReflectionConfiguration {
    // This class is empty, it exists only to hold the @RegisterForReflection annotation
}