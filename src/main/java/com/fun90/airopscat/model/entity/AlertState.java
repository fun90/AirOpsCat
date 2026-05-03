package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "alert_state",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_alert_state_identity",
                        columnNames = {"alert_type", "resource_type", "resource_id", "fingerprint"})
        },
        indexes = {
                @Index(name = "idx_alert_state_type_status", columnList = "alert_type,status"),
                @Index(name = "idx_alert_state_resource", columnList = "resource_type,resource_id")
        }
)
@DynamicUpdate
public class AlertState {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_type", nullable = false)
    private String alertType;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "resource_key")
    private String resourceKey;

    @Column(nullable = false)
    private String fingerprint;

    @Column(nullable = false)
    private String status;

    private String severity;

    @Column(name = "first_triggered_time")
    private LocalDateTime firstTriggeredTime;

    @Column(name = "last_triggered_time")
    private LocalDateTime lastTriggeredTime;

    @Column(name = "last_notified_time")
    private LocalDateTime lastNotifiedTime;

    @Column(name = "recovered_time")
    private LocalDateTime recoveredTime;

    @Column(name = "acknowledged_time")
    private LocalDateTime acknowledgedTime;

    @Column(name = "acknowledged_by", length = 64)
    private String acknowledgedBy;

    @Column(name = "trigger_count")
    private Integer triggerCount;

    @Column(name = "current_value")
    private Double lastValue;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(columnDefinition = "TEXT")
    private String summary;

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
