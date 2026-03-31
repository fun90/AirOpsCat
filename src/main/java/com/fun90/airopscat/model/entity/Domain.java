package com.fun90.airopscat.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fun90.airopscat.model.enums.DnsProviderType;
import com.fun90.airopscat.model.enums.DnsSyncStatus;
import com.fun90.airopscat.util.RawJsonDeserializer;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "domain")
@DynamicUpdate
public class Domain {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "expire_date")
    private LocalDate expireDate;
    
    private String domain;
    
    private BigDecimal price;
    
    private String supplier;

    @Column(name = "dns_provider_config_id")
    private Long dnsProviderConfigId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dns_provider_type")
    private DnsProviderType dnsProviderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "dns_sync_status")
    private DnsSyncStatus dnsSyncStatus;

    @Column(name = "dns_last_sync_time")
    private LocalDateTime dnsLastSyncTime;

    @JsonDeserialize(using = RawJsonDeserializer.class)
    @Column(name = "dns_binding_extension_json", columnDefinition = "json")
    private String dnsBindingExtensionJson;
    
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
