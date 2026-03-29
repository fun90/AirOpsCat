package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "server_monitor_stats",
        indexes = {
                @Index(name = "idx_server_monitor_server_sample_time", columnList = "server_id,sample_time")
        }
)
@DynamicUpdate
public class ServerMonitorStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long serverId;

    private Double cpuUsage;

    private Double memoryUsage;

    private Long memoryUsedBytes;

    private Long memoryTotalBytes;

    private Long networkRxBytes;

    private Long networkTxBytes;

    private Long networkRxRateBytes;

    private Long networkTxRateBytes;

    @Column(nullable = false)
    private LocalDateTime sampleTime;

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
