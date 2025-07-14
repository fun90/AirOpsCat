package com.fun90.airopscat.model.entity;

import io.quarkus.security.jpa.Password;
import io.quarkus.security.jpa.Roles;
import io.quarkus.security.jpa.UserDefinition;
import io.quarkus.security.jpa.Username;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user")
@DynamicUpdate
@UserDefinition
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    @Username
    private String email;
    
    private String nickName;

    private String remarkName;

    @Column(nullable = false)
    @Password
    private String password;
    
    private String remark;
    
    @Roles
    private String role;
    
    private Long referrer;
    
    private Integer disabled;

    private int failedAttempts;

    private LocalDateTime lockTime;
    
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