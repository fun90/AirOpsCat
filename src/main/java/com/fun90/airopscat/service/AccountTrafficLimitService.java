package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.AccountTrafficStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AccountTrafficLimitService {

    public static final String OVER_QUOTA_DOWNLOAD_MBPS_KEY = "airopscat.account.traffic-over-quota.download-mbps";
    public static final String OVER_QUOTA_UPLOAD_MBPS_KEY = "airopscat.account.traffic-over-quota.upload-mbps";

    @Inject
    SystemConfigService systemConfigService;

    public int getOverQuotaDownloadMbps() {
        return Math.max(1, systemConfigService.getIntValue(OVER_QUOTA_DOWNLOAD_MBPS_KEY, 1));
    }

    public int getOverQuotaUploadMbps() {
        return Math.max(1, systemConfigService.getIntValue(OVER_QUOTA_UPLOAD_MBPS_KEY, 1));
    }

    public EffectiveMbpsLimit resolveEffectiveMbps(Account account, AccountTrafficStats currentStats) {
        long usedBytes = currentStats == null ? 0L : safe(currentStats.getUploadBytes()) + safe(currentStats.getDownloadBytes());
        Long effectiveBandwidth = resolveEffectiveBandwidth(account, currentStats);
        if (isOverQuota(usedBytes, effectiveBandwidth)) {
            return new EffectiveMbpsLimit(getOverQuotaDownloadMbps(), getOverQuotaUploadMbps(), true);
        }
        Integer downloadMbps = account == null ? null : account.getDownloadMbps();
        Integer uploadMbps = account == null ? null : account.getUploadMbps();
        return new EffectiveMbpsLimit(downloadMbps, uploadMbps, false);
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

    private long toBytes(long gigabytes) {
        return gigabytes * 1024L * 1024L * 1024L;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    public record EffectiveMbpsLimit(Integer downloadMbps, Integer uploadMbps, boolean trafficOverQuotaLimited) {
    }
}
