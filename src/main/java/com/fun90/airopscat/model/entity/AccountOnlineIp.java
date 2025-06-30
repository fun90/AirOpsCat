package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "account_online_ip")
@DynamicUpdate
public class AccountOnlineIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String accountNo;
    
    @Column(name = "client_ip", nullable = false)
    private String clientIp;
    
    @Column(name = "node_ip", nullable = false)
    private String nodeIp;
    
    @Column(name = "last_online_time", nullable = false)
    private LocalDateTime lastOnlineTime;
    
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