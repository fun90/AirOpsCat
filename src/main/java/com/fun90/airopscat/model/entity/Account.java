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
    
    private LocalDateTime fromDate;
    
    private LocalDateTime toDate;
    
    @Column(nullable = false)
    private String periodType;
    
    @Column(nullable = false, unique = true)
    private String uuid;
    
    private String authCode;
    
    private Integer maxOnlineIps;
    
    private Integer speed;
    
    private Integer bandwidth;
    
    private Integer disabled = 0;
    
    private Long userId;
    
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