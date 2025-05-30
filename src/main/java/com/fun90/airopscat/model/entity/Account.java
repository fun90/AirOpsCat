package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "account")
@DynamicUpdate
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private Integer level;
    
    @Column(name = "from_date")
    private LocalDateTime fromDate;
    
    @Column(name = "to_date")
    private LocalDateTime toDate;
    
    @Column(name = "period_type", nullable = false)
    private String periodType; // DAILY, WEEKLY, MONTHLY, CUSTOM
    
    @Column(nullable = false)
    private String uuid;

    @Column(nullable = false)
    private String accountNo;
    
    @Column(nullable = false)
    private String authCode;
    
    @Column(name = "max_online_ips")
    private Integer maxOnlineIps;
    
    private Integer speed;
    
    private Integer bandwidth;
    
    private Integer disabled = 0;
    
    @Column(name = "user_id")
    private Long userId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;
    
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
    
    // 辅助方法：判断账户是否过期
    @Transient
    public boolean isExpired() {
        if (toDate == null) {
            return false;
        }
        return LocalDateTime.now().isAfter(toDate);
    }
    
    // 辅助方法：判断账户是否激活
    @Transient
    public boolean isActive() {
        return !isExpired() && (disabled == null || disabled == 0);
    }
}