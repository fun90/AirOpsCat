package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.entity.AlertState;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AlertStateVo {
    private Long id;
    private String alertType;
    private String resourceType;
    private Long resourceId;
    private String resourceKey;
    private String fingerprint;
    private String status;
    private String severity;
    private LocalDateTime firstTriggeredTime;
    private LocalDateTime lastTriggeredTime;
    private LocalDateTime lastNotifiedTime;
    private LocalDateTime recoveredTime;
    private LocalDateTime acknowledgedTime;
    private String acknowledgedBy;
    private Integer triggerCount;
    private Double lastValue;
    private Double thresholdValue;
    private String summary;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static AlertStateVo from(AlertState state) {
        if (state == null) return null;
        AlertStateVo vo = new AlertStateVo();
        vo.id = state.getId();
        vo.alertType = state.getAlertType();
        vo.resourceType = state.getResourceType();
        vo.resourceId = state.getResourceId();
        vo.resourceKey = state.getResourceKey();
        vo.fingerprint = state.getFingerprint();
        vo.status = state.getStatus();
        vo.severity = state.getSeverity();
        vo.firstTriggeredTime = state.getFirstTriggeredTime();
        vo.lastTriggeredTime = state.getLastTriggeredTime();
        vo.lastNotifiedTime = state.getLastNotifiedTime();
        vo.recoveredTime = state.getRecoveredTime();
        vo.acknowledgedTime = state.getAcknowledgedTime();
        vo.acknowledgedBy = state.getAcknowledgedBy();
        vo.triggerCount = state.getTriggerCount();
        vo.lastValue = state.getLastValue();
        vo.thresholdValue = state.getThresholdValue();
        vo.summary = state.getSummary();
        vo.createTime = state.getCreateTime();
        vo.updateTime = state.getUpdateTime();
        return vo;
    }
}
