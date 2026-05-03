package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.AcknowledgeRequest;
import com.fun90.airopscat.model.dto.AlertStatePageVo;
import com.fun90.airopscat.model.dto.AlertStateVo;
import com.fun90.airopscat.model.entity.AlertState;
import com.fun90.airopscat.repository.AlertStateRepository;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AlertStateService {

    @Inject
    AlertStateRepository alertStateRepository;

    public AlertStatePageVo getPage(String alertType, String status, String resourceType, int page, int size) {
        int safeSize = Math.max(1, size);
        int safePage = Math.max(page, 1);
        PanacheQuery<AlertState> query = alertStateRepository.findByFilters(alertType, status, resourceType);
        long total = query.count();
        List<AlertStateVo> records = query.page(Page.of(safePage - 1, safeSize))
                .list()
                .stream()
                .map(AlertStateVo::from)
                .toList();

        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / safeSize);
        return new AlertStatePageVo(records, total, pages, safePage, safeSize);
    }

    public Map<String, Long> getStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("active", alertStateRepository.countByStatus("ACTIVE"));
        stats.put("acknowledged", alertStateRepository.countByStatus("ACKNOWLEDGED"));
        stats.put("recovered", alertStateRepository.countByStatus("RECOVERED"));
        return stats;
    }

    public AlertStateVo getById(Long id) {
        AlertState state = alertStateRepository.findById(id);
        if (state == null) throw new NotFoundException("告警记录不存在: " + id);
        return AlertStateVo.from(state);
    }

    @Transactional
    public AlertStateVo acknowledge(Long id, AcknowledgeRequest request, String defaultAcknowledgedBy) {
        AlertState state = alertStateRepository.findById(id);
        if (state == null) throw new NotFoundException("告警记录不存在: " + id);
        if (!"ACTIVE".equals(state.getStatus())) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("告警状态不为 ACTIVE，当前状态: " + state.getStatus())
                            .build());
        }
        state.setStatus("ACKNOWLEDGED");
        state.setAcknowledgedTime(LocalDateTime.now());
        String acknowledgedBy = request != null ? request.getAcknowledgedBy() : null;
        state.setAcknowledgedBy(acknowledgedBy == null || acknowledgedBy.isBlank() ? defaultAcknowledgedBy : acknowledgedBy);
        return AlertStateVo.from(state);
    }

    @Transactional
    public void delete(Long id) {
        AlertState state = alertStateRepository.findById(id);
        if (state == null) throw new NotFoundException("告警记录不存在: " + id);
        alertStateRepository.delete(state);
    }
}
