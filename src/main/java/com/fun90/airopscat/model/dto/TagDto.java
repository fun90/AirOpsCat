package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TagDto {
    private Long id;
    private String name;
    private String description;
    private String color;
    private Integer disabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    
    // 统计信息
    private Integer nodeCount;    // 关联的节点数量
    private Integer accountCount; // 关联的账户数量
    
    // 关联的节点ID列表（用于标签管理）
    private List<Long> nodeIds;
    
    // 关联的账户ID列表（用于标签管理）
    private List<Long> accountIds;
    
    // 辅助方法：判断标签是否启用
    public boolean isEnabled() {
        return disabled == null || disabled == 0;
    }
} 