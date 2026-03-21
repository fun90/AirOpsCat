package com.fun90.airopscat.model.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fun90.airopscat.util.RawJsonDeserializer;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Entity
@Table(name = "route_rule")
@DynamicUpdate
public class RouteRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "core_type", nullable = false)
    private String coreType;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    @JsonDeserialize(using = RawJsonDeserializer.class)
    @Column(name = "rule_value", columnDefinition = "json", nullable = false)
    private String ruleValue;

    @Column(name = "outbound_node_id", nullable = false)
    private Long outboundNodeId;

    private Integer enabled;

    private String remark;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "route_rule_server",
            joinColumns = @JoinColumn(name = "route_rule_id"),
            inverseJoinColumns = @JoinColumn(name = "server_id")
    )
    private Set<Server> servers = new LinkedHashSet<>();

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
}
