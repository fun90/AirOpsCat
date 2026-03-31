package com.fun90.airopscat.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.util.RawJsonDeserializer;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "domain_dns_record")
@DynamicUpdate
public class DomainDnsRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "domain_id", nullable = false)
    private Long domainId;

    @Column(name = "dns_provider_config_id")
    private Long dnsProviderConfigId;

    @Column(name = "external_record_id")
    private String externalRecordId;

    @Column(nullable = false)
    private String name;

    @Column(name = "full_name")
    private String fullName;

    @Column(nullable = false)
    private String type;

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    private Integer ttl;

    private Boolean proxied;

    private Integer priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DnsRecordStatus status;

    private String remark;

    @JsonDeserialize(using = RawJsonDeserializer.class)
    @Column(name = "biz_tags_json", columnDefinition = "json")
    private String bizTagsJson;

    @JsonDeserialize(using = RawJsonDeserializer.class)
    @Column(name = "extension_json", columnDefinition = "json")
    private String extensionJson;

    @JsonDeserialize(using = RawJsonDeserializer.class)
    @Column(name = "raw_data", columnDefinition = "json")
    private String rawData;

    @Column(name = "last_sync_time")
    private LocalDateTime lastSyncTime;

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
