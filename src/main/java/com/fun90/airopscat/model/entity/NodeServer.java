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

    // 部署位域名：domain_dns_record.id，订阅 host 优先取它，一条记录只能被一个部署位或账户订阅定制引用
    @Column(name = "access_host_id")
    private Long accessHostId;

    // 是否出现在订阅；0 表示只部署，可作自动切换的备用
    private Integer exposed;

    // 越小越优先
    private Integer priority;

    // 0:待部署,1:已部署,2:待删除
    private Integer deployed;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "server_id", insertable = false, updatable = false)
    private Server server;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "access_host_id", insertable = false, updatable = false)
    private DomainDnsRecord accessHost;

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

    // 非 getter 命名，避免与 exposed 字段的序列化冲突
    public boolean exposedFlag() {
        return exposed == null || exposed == 1;
    }
}
