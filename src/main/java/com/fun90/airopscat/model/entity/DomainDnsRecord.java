package com.fun90.airopscat.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.util.RawJsonDeserializer;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.time.LocalTime;

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

    @Column(name = "schedule_enabled")
    private Boolean scheduleEnabled;

    /** 时段起点 HH:mm（含），Asia/Shanghai */
    @Column(name = "schedule_start", length = 5)
    private String scheduleStart;

    /** 时段终点 HH:mm（不含）；早于起点表示跨零点 */
    @Column(name = "schedule_end", length = 5)
    private String scheduleEnd;

    @Column(name = "schedule_content_in")
    private String scheduleContentIn;

    @Column(name = "schedule_content_out")
    private String scheduleContentOut;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 按当前时间返回应解析的内容：时段内取 contentIn，其余取 contentOut */
    public String scheduledContent(LocalTime now) {
        LocalTime start = LocalTime.parse(scheduleStart);
        LocalTime end = LocalTime.parse(scheduleEnd);
        boolean in = start.isBefore(end)
                ? !now.isBefore(start) && now.isBefore(end)
                : !now.isBefore(start) || now.isBefore(end);
        return in ? scheduleContentIn : scheduleContentOut;
    }

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
