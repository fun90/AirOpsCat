package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "server")
@DynamicUpdate
public class Server {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String ip;
    
    @Column(name = "ssh_port")
    private Integer sshPort;
    
    @Column(name = "auth_type")
    private String authType;
    
    private String auth;
    
    private String host;
    
    private String name;
    
    @Column(name = "expire_date")
    private LocalDate expireDate;
    
    private String supplier;
    
    private BigDecimal price;
    
    private BigDecimal multiple;
    
    private Integer disabled = 0;
    
    private String remark;
    
    @Column(name = "transit_config", columnDefinition = "json")
    private String transitConfig;
    
    @Column(name = "core_config", columnDefinition = "json")
    private String coreConfig;
    
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
    
    // 辅助方法：判断服务器是否过期
    @Transient
    public boolean isExpired() {
        if (expireDate == null) {
            return false;
        }
        return LocalDate.now().isAfter(expireDate);
    }
    
    // 辅助方法：判断服务器是否激活
    @Transient
    public boolean isActive() {
        return !isExpired() && (disabled == null || disabled == 0);
    }
}