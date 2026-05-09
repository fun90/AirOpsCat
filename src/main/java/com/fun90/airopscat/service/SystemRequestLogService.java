package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.SystemRequestLogPageVo;
import com.fun90.airopscat.model.dto.SystemRequestLogPathStatsVo;
import com.fun90.airopscat.model.dto.SystemRequestLogQuery;
import com.fun90.airopscat.model.dto.SystemRequestLogStatsItemVo;
import com.fun90.airopscat.model.dto.SystemRequestLogStatsVo;
import com.fun90.airopscat.model.dto.SystemRequestLogVo;
import com.fun90.airopscat.model.entity.SystemRequestLog;
import com.fun90.airopscat.repository.SystemRequestLogRepository;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class SystemRequestLogService {
    private static final int MAX_METHOD_LENGTH = 16;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_QUERY_LENGTH = 1024;
    private static final int MAX_IP_LENGTH = 128;
    private static final int MAX_HEADER_LENGTH = 512;

    @Inject
    SystemRequestLogRepository systemRequestLogRepository;

    @Inject
    SystemRequestLogProperties systemRequestLogProperties;

    public SystemRequestLogPageVo getPage(SystemRequestLogQuery query, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(page, 1);
        PanacheQuery<SystemRequestLog> panacheQuery = systemRequestLogRepository.findByQuery(query);
        long total = panacheQuery.count();
        List<SystemRequestLogVo> records = panacheQuery.page(Page.of(safePage - 1, safeSize))
                .list()
                .stream()
                .map(SystemRequestLogVo::from)
                .toList();
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / safeSize);
        return new SystemRequestLogPageVo(records, total, pages, safePage, safeSize);
    }

    public SystemRequestLogStatsVo getStats(SystemRequestLogQuery query) {
        return new SystemRequestLogStatsVo(
                statsByConfiguredPathAndDate(query),
                systemRequestLogRepository.statsByClientIp(query, 10)
        );
    }

    @Transactional
    public void record(String method,
                       String requestPath,
                       String queryString,
                       String clientIp,
                       Integer statusCode,
                       long durationMillis,
                       String userAgent,
                       String referer,
                       LocalDateTime accessTime) {
        if (!systemRequestLogProperties.matches(requestPath)) {
            return;
        }
        try {
            SystemRequestLog requestLog = new SystemRequestLog();
            requestLog.setAccessTime(accessTime == null ? LocalDateTime.now() : accessTime);
            requestLog.setMethod(truncate(method, MAX_METHOD_LENGTH, "GET"));
            requestLog.setRequestPath(truncate(requestPath, MAX_PATH_LENGTH, "/"));
            requestLog.setQueryString(truncate(queryString, MAX_QUERY_LENGTH, null));
            requestLog.setClientIp(truncate(clientIp, MAX_IP_LENGTH, "unknown"));
            requestLog.setStatusCode(statusCode);
            requestLog.setDurationMillis(Math.max(0L, durationMillis));
            requestLog.setUserAgent(truncate(userAgent, MAX_HEADER_LENGTH, null));
            requestLog.setReferer(truncate(referer, MAX_HEADER_LENGTH, null));
            systemRequestLogRepository.persist(requestLog);
        } catch (Exception e) {
            log.warn("记录系统请求日志失败: {} {}", method, requestPath, e);
        }
    }

    @Transactional
    public long cleanupExpiredLogs() {
        int retentionDays = systemRequestLogProperties.getRetentionDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        return systemRequestLogRepository.deleteExpired(cutoff);
    }

    private String truncate(String value, int maxLength, String defaultValue) {
        String normalized = value == null || value.isBlank() ? defaultValue : value.trim();
        if (normalized == null) {
            return null;
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private List<SystemRequestLogPathStatsVo> statsByConfiguredPathAndDate(SystemRequestLogQuery query) {
        Map<String, Map<String, Long>> grouped = new LinkedHashMap<>();
        for (SystemRequestLogPathStatsVo rawPathStats : systemRequestLogRepository.statsByPathAndDate(query)) {
            String configuredPath = systemRequestLogProperties.findMatchedPattern(rawPathStats.getRequestPath());
            if (configuredPath == null) {
                continue;
            }
            Map<String, Long> dateCounts = grouped.computeIfAbsent(configuredPath, key -> new LinkedHashMap<>());
            for (SystemRequestLogStatsItemVo dateStat : rawPathStats.getDateStats()) {
                dateCounts.merge(dateStat.getLabel(), dateStat.getCount(), Long::sum);
            }
        }

        return systemRequestLogProperties.getPaths().stream()
                .filter(grouped::containsKey)
                .map(configuredPath -> new SystemRequestLogPathStatsVo(
                        configuredPath,
                        grouped.get(configuredPath).entrySet().stream()
                                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                                .map(entry -> new SystemRequestLogStatsItemVo(entry.getKey(), entry.getValue()))
                                .toList()
                ))
                .toList();
    }
}
