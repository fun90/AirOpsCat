package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用系统日志：按 category 区分来源，来源特有的结构化信息放 detail（JSON 文本）。
 */
@Data
@Entity
@Table(
        name = "system_log",
        indexes = {
                @Index(name = "idx_system_log_category_time", columnList = "category,create_time"),
                @Index(name = "idx_system_log_resource", columnList = "category,resource_id")
        }
)
public class SystemLog {
    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String category;

    // INFO / WARN / ERROR
    @Column(nullable = false, length = 8)
    private String level;

    // 关联资源（如节点），可空
    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "resource_name", length = 257)
    private String resourceName;

    @Column(nullable = false, length = 512)
    private String message;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
    }
}
