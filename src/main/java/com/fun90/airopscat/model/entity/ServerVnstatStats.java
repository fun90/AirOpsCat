package com.fun90.airopscat.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "server_vnstat_stats")
public class ServerVnstatStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long serverId;

    @Column(nullable = false, length = 32)
    private String iface;

    @Column(nullable = false)
    private Short periodYear;

    @Column(nullable = false)
    private Byte periodMonth;

    @Column(nullable = false)
    private Long rxBytes = 0L;

    @Column(nullable = false)
    private Long txBytes = 0L;

    @Column(nullable = false)
    private LocalDateTime sampledAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }
}
