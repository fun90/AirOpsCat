package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "account_traffic_stats",
        indexes = {
                @Index(name = "idx_account_traffic_account_period", columnList = "account_id,period_start,period_end"),
                @Index(name = "idx_account_traffic_user_period", columnList = "user_id,period_start,period_end")
        }
)
@DynamicUpdate
public class AccountTrafficStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long userId;
    
    @Column(nullable = false)
    private Long accountId;
    
    @Column(nullable = false)
    private LocalDateTime periodStart;
    
    @Column(nullable = false)
    private LocalDateTime periodEnd;
    
    private Long bandwidthQuota;

    private Long uploadBytes = 0L;

    private Long downloadBytes = 0L;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "user_id", insertable = false, updatable = false)
//    private User user;
    
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
