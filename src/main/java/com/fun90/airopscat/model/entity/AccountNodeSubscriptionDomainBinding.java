package com.fun90.airopscat.model.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Entity
@Table(
        name = "account_node_subscription_domain_binding",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_account_node_subscription_domain", columnNames = {"account_id", "node_id"})
        },
        indexes = {
                @Index(name = "idx_ansd_account_id", columnList = "account_id"),
                @Index(name = "idx_ansd_node_id", columnList = "node_id"),
                @Index(name = "idx_ansd_domain_dns_record_id", columnList = "domain_dns_record_id"),
                @Index(name = "idx_ansd_enabled", columnList = "enabled")
        }
)
@DynamicUpdate
public class AccountNodeSubscriptionDomainBinding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    /** 为空时不覆盖连接地址，仅按选定部署位定制 */
    @Column(name = "domain_dns_record_id")
    private Long domainDnsRecordId;

    /** 订阅中展示的部署位；为空时沿用默认规则 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "account_node_subscription_placement", joinColumns = @JoinColumn(name = "binding_id"))
    @Column(name = "node_server_id", nullable = false)
    private Set<Long> nodeServerIds = new LinkedHashSet<>();

    private Integer enabled = 1;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", insertable = false, updatable = false)
    private Node node;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "domain_dns_record_id", insertable = false, updatable = false)
    private DomainDnsRecord domainDnsRecord;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
        if (this.enabled == null) {
            this.enabled = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
