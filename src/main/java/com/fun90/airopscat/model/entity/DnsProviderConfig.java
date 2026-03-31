package com.fun90.airopscat.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fun90.airopscat.config.CryptoConverter;
import com.fun90.airopscat.model.enums.DnsProviderCheckStatus;
import com.fun90.airopscat.model.enums.DnsProviderConfigStatus;
import com.fun90.airopscat.model.enums.DnsProviderType;
import com.fun90.airopscat.util.RawJsonDeserializer;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "dns_provider_config")
@DynamicUpdate
public class DnsProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false)
    private DnsProviderType providerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DnsProviderConfigStatus status;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "credential_json", columnDefinition = "text")
    private String credentialJson;

    @JsonDeserialize(using = RawJsonDeserializer.class)
    @Column(name = "extension_json", columnDefinition = "json")
    private String extensionJson;

    @Column(name = "last_check_time")
    private LocalDateTime lastCheckTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_check_status")
    private DnsProviderCheckStatus lastCheckStatus;

    @Column(name = "last_check_message", columnDefinition = "text")
    private String lastCheckMessage;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
