package com.fun90.airopscat.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fun90.airopscat.util.RawJsonDeserializer;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Entity
@Table(name = "node")
@DynamicUpdate
public class Node {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "server_id")
    private Long serverId;
    
    @Column(name = "backup_server_id")
    private Long backupServerId;
    
    private Integer port;

    // 代理协议：VLESS、Hysteria2、Socks、Shadowsocks、ShadowTLS
    private String protocol;

    @Column(name = "core_type")
    private String coreType;
    
    private Integer type; // 0:代理，1:落地

    @JsonDeserialize(using = RawJsonDeserializer.class)
    @Column(columnDefinition = "json")
    private String inbound;

    @Column(name = "out_id")
    private Long outId;

    @JsonDeserialize(using = RawJsonDeserializer.class)
    @Column(columnDefinition = "json")
    private String rule;
    
    private Integer level;

    // 0:未部署,1:已部署
    private Integer deployed;

    private Integer disabled;
    
    private String name;

    private Integer no;
    
    private String remark;
    
//    @ManyToOne(fetch = FetchType.LAZY)
    @ManyToOne(fetch = FetchType.EAGER, cascade = {CascadeType.MERGE, CascadeType.REFRESH})
    @JoinColumn(name = "server_id", insertable = false, updatable = false)
    private Server server;

    @ManyToOne(fetch = FetchType.EAGER, cascade = {CascadeType.MERGE, CascadeType.REFRESH})
    @JoinColumn(name = "backup_server_id", insertable = false, updatable = false)
    private Server backupServer;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "out_id", insertable = false, updatable = false)
    private Node outNode;
    
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
        name = "node_tag",
        joinColumns = @JoinColumn(name = "node_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();
    
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

    // 辅助方法：获取节点类型描述
    @Transient
    public String getTypeDescription() {
        if (type == null) {
            return "未知";
        }
        return type == 0 ? "代理" : "落地";
    }

    // 辅助方法：获取tag
    public String getTag() {
        return "node_" + id;
    }
}
