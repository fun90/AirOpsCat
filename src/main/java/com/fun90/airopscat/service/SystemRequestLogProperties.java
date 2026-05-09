package com.fun90.airopscat.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class SystemRequestLogProperties {
    private static final String ENABLED_KEY = "airopscat.request-log.enabled";
    private static final String PATHS_KEY = "airopscat.request-log.paths";
    private static final String RETENTION_DAYS_KEY = "airopscat.request-log.retention-days";

    @Inject
    SystemConfigService systemConfigService;

    public boolean isEnabled() {
        return systemConfigService.getBooleanValue(ENABLED_KEY, true);
    }

    public List<String> getPaths() {
        String paths = systemConfigService.getResolvedValue(PATHS_KEY);
        if (paths == null || paths.isBlank()) {
            return List.of();
        }
        return Arrays.stream(paths.split(","))
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .toList();
    }

    public int getRetentionDays() {
        return Math.max(1, systemConfigService.getIntValue(RETENTION_DAYS_KEY, 30));
    }

    public boolean matches(String path) {
        if (!isEnabled() || path == null || path.isBlank()) {
            return false;
        }
        return getPaths().stream().anyMatch(pattern -> matches(pattern, path));
    }

    private boolean matches(String pattern, String path) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String normalizedPattern = pattern.trim();
        if (normalizedPattern.endsWith("/*")) {
            String prefix = normalizedPattern.substring(0, normalizedPattern.length() - 1);
            return path.startsWith(prefix);
        }
        return path.equals(normalizedPattern);
    }
}
