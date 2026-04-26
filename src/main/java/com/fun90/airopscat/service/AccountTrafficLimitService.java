package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AccountTrafficLimitService {

    public static final String OVER_QUOTA_SPEED_KEY = "airopscat.account.traffic-over-quota.speed-kb";
    public static final int DEFAULT_OVER_QUOTA_SPEED_KB = 20;

    @Inject
    SystemConfigService systemConfigService;

    public EffectiveSpeedLimit resolveEffectiveSpeed(Account account, AccountTrafficStats currentStats) {
        Long effectiveBandwidth = resolveEffectiveBandwidth(account, currentStats);
        long usedBytes = currentStats == null ? 0L : safe(currentStats.getUploadBytes()) + safe(currentStats.getDownloadBytes());
        return resolveEffectiveSpeed(account, usedBytes, effectiveBandwidth);
    }

    public EffectiveSpeedLimit resolveEffectiveSpeed(Account account, long usedBytes, Long effectiveBandwidth) {
        Integer accountSpeed = normalizeSpeed(account == null ? null : account.getSpeed());
        if (!isOverQuota(usedBytes, effectiveBandwidth)) {
            return new EffectiveSpeedLimit(accountSpeed, false);
        }

        int overQuotaSpeed = getOverQuotaSpeedKb();
        if (accountSpeed != null && accountSpeed <= overQuotaSpeed) {
            return new EffectiveSpeedLimit(accountSpeed, false);
        }
        return new EffectiveSpeedLimit(overQuotaSpeed, true);
    }

    public Long resolveEffectiveBandwidth(Account account, AccountTrafficStats currentStats) {
        if (currentStats != null && currentStats.getBandwidthQuota() != null) {
            return currentStats.getBandwidthQuota();
        }
        return account != null && account.getBandwidth() != null ? account.getBandwidth().longValue() : null;
    }

    public boolean isOverQuota(long usedBytes, Long effectiveBandwidth) {
        if (effectiveBandwidth == null || effectiveBandwidth <= 0) {
            return false;
        }
        return usedBytes >= toBytes(effectiveBandwidth);
    }

    public int getOverQuotaSpeedKb() {
        return Math.max(1, systemConfigService.getIntValue(OVER_QUOTA_SPEED_KEY, DEFAULT_OVER_QUOTA_SPEED_KB));
    }

    private long toBytes(long gigabytes) {
        return gigabytes * 1024L * 1024L * 1024L;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    private Integer normalizeSpeed(Integer speed) {
        return speed != null && speed > 0 ? speed : null;
    }

    public record EffectiveSpeedLimit(Integer speed, boolean trafficOverQuotaLimited) {
    }
}
