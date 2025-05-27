package com.fun90.airopscat.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fun90.airopscat.support.util.RawJsonDeserializer;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

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
    
    private Integer port;

    // 代理协议：VLESS、Hysteria2、Socks、Shadowsocks、ShadowTLS
    private String protocol;
    
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
    
    private String remark;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", insertable = false, updatable = false)
    private Server server;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "out_id", insertable = false, updatable = false)
    private Node outNode;
    
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
}