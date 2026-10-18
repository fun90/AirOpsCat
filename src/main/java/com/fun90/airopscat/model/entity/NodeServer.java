package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import com.fun90.airopscat.util.TimeWindow;

import java.time.LocalDateTime;
import java.time.LocalTime;

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

    // 部署位域名：domain_dns_record.id，订阅 host 优先取它，可与选择本部署位的账户订阅定制共用
    @Column(name = "access_host_id")
    private Long accessHostId;

    // 是否出现在订阅；0 表示只部署，可作自动切换的备用
    private Integer exposed;

    // 越小越优先
    private Integer priority;

    // 0:待部署,1:已部署,2:待删除
    private Integer deployed;

    // 便宜时段 HH:mm（Asia/Shanghai，起点含、终点不含，早于起点表示跨零点）；时段内同节点各部署位的域名优先指向本部署位的服务器
    @Column(name = "cheap_start", length = 5)
    private String cheapStart;

    @Column(name = "cheap_end", length = 5)
    private String cheapEnd;

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

    public boolean cheapActive(LocalTime now) {
        return TimeWindow.contains(cheapStart, cheapEnd, now);
    }

    // 非 getter 命名，避免与 exposed 字段的序列化冲突
    public boolean exposedFlag() {
        return exposed == null || exposed == 1;
    }
}
