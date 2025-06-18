package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Entity
@Table(name = "tag")
@DynamicUpdate
public class Tag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String name;
    
    private String description;
    
    private String color; // 标签颜色，用于前端显示
    
    private Integer disabled = 0; // 0:启用，1:禁用
    
    @ManyToMany(mappedBy = "tags", fetch = FetchType.LAZY)
    private Set<Node> nodes = new HashSet<>();
    
    @ManyToMany(mappedBy = "tags", fetch = FetchType.LAZY)
    private Set<Account> accounts = new HashSet<>();
    
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
    
    // 注意：这些方法可能触发懒加载，不建议在DTO转换中使用
    // 辅助方法：获取关联的节点数量
    @Transient
    public int getNodeCount() {
        try {
            return nodes != null ? nodes.size() : 0;
        } catch (Exception e) {
            // 如果懒加载失败，返回0
            return 0;
        }
    }
    
    // 辅助方法：获取关联的账户数量
    @Transient
    public int getAccountCount() {
        try {
            return accounts != null ? accounts.size() : 0;
        } catch (Exception e) {
            // 如果懒加载失败，返回0
            return 0;
        }
    }
    
    // 辅助方法：判断标签是否启用
    @Transient
    public boolean isEnabled() {
        return disabled == null || disabled == 0;
    }
} 