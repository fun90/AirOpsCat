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
@Table(
        name = "node",
        indexes = {
                @Index(name = "idx_node_out_id", columnList = "out_id"),
                @Index(name = "idx_node_type_core_type", columnList = "type,core_type"),
                @Index(name = "idx_node_dns_record_id", columnList = "dns_record_id")
        }
)
@DynamicUpdate
public class Node {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


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

    private Integer disabled;
    
    @Column(length = 257)
    private String name;

    private String remark;

    // 绑定后订阅只输出一条，host 为该记录域名
    @Column(name = "dns_record_id")
    private Long dnsRecordId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "out_id", insertable = false, updatable = false)
    private Node outNode;
    
    // 旧 node_tag 只供迁移及回滚；访问权限读取 NodeServer.tags
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
