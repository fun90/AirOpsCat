package com.fun90.airopscat.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AccountTrafficStatsDto {
    private Long id;
    private Long userId;
    private String userEmail;
    private String nickname;
    private Long accountId;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private Long uploadBytes;
    private Long downloadBytes;
    private Long totalBytes;
    private Long bandwidthQuota;

    public AccountTrafficStatsDto(Long id, Long userId, String nickname, Long accountId,
                                  LocalDateTime periodStart, LocalDateTime periodEnd,
                                  Long uploadBytes, Long downloadBytes, Long totalBytes,
                                  Long bandwidthQuota) {
        this.id = id;
        this.userId = userId;
        this.nickname = nickname;
        this.accountId = accountId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.uploadBytes = uploadBytes;
        this.downloadBytes = downloadBytes;
        this.totalBytes = totalBytes;
        this.bandwidthQuota = bandwidthQuota;
    }
}
