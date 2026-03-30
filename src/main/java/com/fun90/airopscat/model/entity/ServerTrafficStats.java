package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "server_traffic_stats")
@DynamicUpdate
public class ServerTrafficStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long serverId;

    @Column(nullable = false)
    private LocalDateTime periodStart;

    @Column(nullable = false)
    private LocalDateTime periodEnd;

    private Long uploadBytes = 0L;

    private Long downloadBytes = 0L;

    private Long monitorUploadAdjustmentBytes = 0L;

    private Long monitorDownloadAdjustmentBytes = 0L;

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
