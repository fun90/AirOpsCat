package com.fun90.airopscat.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "node_online_account_daily_stats",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"node_id", "stat_date"})},
        indexes = {
                @Index(name = "idx_node_online_daily_node_date", columnList = "node_id,stat_date"),
                @Index(name = "idx_node_online_daily_stat_date", columnList = "stat_date")
        }
)
@DynamicUpdate
public class NodeOnlineAccountDailyStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "latest_online_account_count", nullable = false)
    private Integer latestOnlineAccountCount = 0;

    @Column(name = "peak_online_account_count", nullable = false)
    private Integer peakOnlineAccountCount = 0;

    @Column(name = "unique_online_account_count", nullable = false)
    private Integer uniqueOnlineAccountCount = 0;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount = 0;

    @Column(name = "last_sample_time", nullable = false)
    private LocalDateTime lastSampleTime;

    @Column(name = "seen_account_nos_json", columnDefinition = "json")
    private String seenAccountNosJson;

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
