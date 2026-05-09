package com.fun90.airopscat.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "system_request_log",
        indexes = {
                @Index(name = "idx_system_request_log_access_time", columnList = "access_time"),
                @Index(name = "idx_system_request_log_path", columnList = "request_path"),
                @Index(name = "idx_system_request_log_client_ip", columnList = "client_ip")
        }
)
public class SystemRequestLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "access_time", nullable = false)
    private LocalDateTime accessTime;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(name = "request_path", nullable = false, length = 512)
    private String requestPath;

    @Column(name = "query_string", length = 1024)
    private String queryString;

    @Column(name = "client_ip", nullable = false, length = 128)
    private String clientIp;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "duration_millis")
    private Long durationMillis;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(length = 512)
    private String referer;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
