package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "node_deployment")
@DynamicUpdate
public class NodeDeployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false, unique = true)
    private Long nodeId;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;

    @Column(name = "snapshot_json", columnDefinition = "json")
    private String snapshotJson;

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "node_group")
    private String nodeGroup;

    @Column(name = "core_type")
    private String coreType;

    private String protocol;

    private Integer type;

    private Integer port;

    private Integer disabled;

    @Column(name = "deployed_at")
    private LocalDateTime deployedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
