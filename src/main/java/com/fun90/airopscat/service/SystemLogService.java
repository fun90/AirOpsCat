package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.SystemLogPageVo;
import com.fun90.airopscat.model.entity.SystemLog;
import com.fun90.airopscat.model.enums.SystemLogCategory;
import com.fun90.airopscat.repository.SystemLogRepository;
import com.fun90.airopscat.util.JsonUtil;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class SystemLogService {

    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final int MAX_NAME_LENGTH = 257;

    @Inject
    SystemLogRepository systemLogRepository;

    @Inject
    SystemConfigService systemConfigService;

    /** 加入调用方事务：日志与业务操作同成败 */
    @Transactional
    public void record(SystemLogCategory category, String level, Long resourceId, String resourceName,
                       String message, Map<String, Object> detail) {
        SystemLog entry = new SystemLog();
        entry.setCategory(category.name());
        entry.setLevel(level);
        entry.setResourceId(resourceId);
        entry.setResourceName(truncate(resourceName, MAX_NAME_LENGTH));
        entry.setMessage(truncate(message, MAX_MESSAGE_LENGTH));
        entry.setDetail(detail == null || detail.isEmpty() ? null : JsonUtil.toJsonString(detail));
        systemLogRepository.persist(entry);
    }

    /** 独立事务：业务事务回滚时仍要留痕的日志（如失败）；写入失败只记警告 */
    public void recordIndependently(SystemLogCategory category, String level, Long resourceId, String resourceName,
                                    String message, Map<String, Object> detail) {
        try {
            QuarkusTransaction.requiringNew().run(() ->
                    record(category, level, resourceId, resourceName, message, detail));
        } catch (RuntimeException e) {
            log.warn("写入系统日志失败, category={}: {}", category, e.getMessage());
        }
    }

    public SystemLogPageVo getPage(String category, String level, String keyword,
                                   LocalDateTime start, LocalDateTime end, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(page, 1);
        PanacheQuery<SystemLog> query = systemLogRepository.findByQuery(category, level, keyword, start, end);
        long total = query.count();
        List<SystemLog> records = query.page(Page.of(safePage - 1, safeSize)).list();
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / safeSize);
        return new SystemLogPageVo(records, total, pages, safePage, safeSize);
    }

    @Transactional
    public long cleanupExpiredLogs() {
        int days = Math.max(systemConfigService.getIntValue("airopscat.system-log.retention-days", 90), 1);
        return systemLogRepository.deleteOlderThan(LocalDateTime.now().minusDays(days));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
