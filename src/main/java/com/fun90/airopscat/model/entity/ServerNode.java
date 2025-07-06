package com.fun90.airopscat.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fun90.airopscat.utils.RawJsonDeserializer;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "server_node")
@DynamicUpdate
public class ServerNode {
    @Id
    private Long id;
    
    @Column(name = "server_id")
    private Long serverId;
    
    private Integer port;

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
    
    private Integer disabled;
    
    private String name;
    
    private String remark;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", insertable = false, updatable = false)
    private Server server;
    
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id", insertable = false, updatable = false)
    private Node node;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "out_id", insertable = false, updatable = false)
    private Node outNode;
    
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
