package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "account_online_ip",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"account_no", "node_ip", "connection_id"})},
        indexes = {
                @Index(name = "idx_account_online_account_time", columnList = "account_no,last_online_time"),
                @Index(name = "idx_account_online_node_time", columnList = "node_ip,last_online_time"),
                @Index(name = "idx_account_online_logic_node_time", columnList = "node_id,last_online_time"),
                @Index(name = "idx_account_online_last_time", columnList = "last_online_time")
        }
)
@DynamicUpdate
public class AccountOnlineIp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String accountNo;
    
    @Column(name = "client_ip", nullable = false)
    private String clientIp;

    @Column(name = "connection_id")
    private String connectionId;
    
    @Column(name = "node_ip", nullable = false)
    private String nodeIp;

    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "node_tag")
    private String nodeTag;
    
    @Column(name = "last_online_time", nullable = false)
    private LocalDateTime lastOnlineTime;

    @Column(name = "session_start_time")
    private LocalDateTime sessionStartTime;
    
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
