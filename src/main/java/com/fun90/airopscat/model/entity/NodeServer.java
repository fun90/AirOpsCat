package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 部署位：逻辑节点部署到某台服务器的实例。
 */
@Data
@Entity
@Table(
        name = "node_server",
        uniqueConstraints = @UniqueConstraint(name = "uk_node_server_node_server", columnNames = {"node_id", "server_id"}),
        indexes = {
                @Index(name = "idx_node_server_server_id", columnList = "server_id"),
                @Index(name = "idx_node_server_access_host_id", columnList = "access_host_id")
        }
)
@DynamicUpdate
public class NodeServer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "access_host_id")
    private Long accessHostId;

    // 未绑定 DNS 时表示是否出现在订阅；绑定 DNS 时表示是否参与切换
    private Integer exposed;

    // 越小越优先
    private Integer priority;

    // 0 表示不部署到该服务器
    private Integer enabled;

    // 订阅显示名覆盖
    @Column(length = 257)
    private String name;

    // 0:待部署,1:已部署,2:待删除
    private Integer deployed;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "server_id", insertable = false, updatable = false)
    private Server server;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "access_host_id", insertable = false, updatable = false)
    private ServerHost accessHost;

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

    // 非 getter 命名，避免与 enabled/exposed 字段的序列化冲突
    public boolean enabledFlag() {
        return enabled == null || enabled == 1;
    }

    public boolean exposedFlag() {
        return exposed == null || exposed == 1;
    }
}
