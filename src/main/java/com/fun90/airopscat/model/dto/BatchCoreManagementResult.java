package com.fun90.airopscat.model.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 批量内核管理操作结果
 */
@Data
public class BatchCoreManagementResult {
    /**
     * 操作类型
     */
    private String operation;
    
    /**
     * 内核类型
     */
    private String coreType;
    
    /**
     * 操作开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 操作结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 各服务器的操作结果
     */
    private Map<String, CoreManagementResult> results = new HashMap<>();
    
    /**
     * 成功数量
     */
    private int successCount;
    
    /**
     * 失败数量
     */
    private int failureCount;
    
    /**
     * 总数量
     */
    private int totalCount;
    
    public void addResult(String serverAddress, CoreManagementResult result) {
        results.put(serverAddress, result);
        if (result.isSuccess()) {
            successCount++;
        } else {
            failureCount++;
        }
        totalCount++;
    }
    
    public boolean isAllSuccess() {
        return failureCount == 0 && totalCount > 0;
    }
    
    public double getSuccessRate() {
        return totalCount > 0 ? (double) successCount / totalCount : 0.0;
    }
}