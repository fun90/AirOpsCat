package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 内核管理操作结果
 */
@Data
public class CoreManagementResult {
    /**
     * 操作是否成功
     */
    private boolean success;
    
    /**
     * 操作类型
     */
    private String operation;
    
    /**
     * 内核类型
     */
    private String coreType;
    
    /**
     * 服务器地址
     */
    private String serverAddress;
    
    /**
     * 结果消息
     */
    private String message;
    
    /**
     * 详细输出
     */
    private String output;
    
    /**
     * 错误信息
     */
    private String error;
    
    /**
     * 操作时间
     */
    private LocalDateTime operationTime;
    
    /**
     * 退出码
     */
    private int exitCode;
    
    /**
     * 扩展数据
     */
    private Map<String, Object> metadata;
    
    /**
     * 执行耗时（毫秒）
     */
    private long duration;
    
    public static CoreManagementResult success(String operation, String coreType, String message) {
        CoreManagementResult result = new CoreManagementResult();
        result.setSuccess(true);
        result.setOperation(operation);
        result.setCoreType(coreType);
        result.setMessage(message);
        result.setOperationTime(LocalDateTime.now());
        result.setExitCode(0);
        return result;
    }
    
    public static CoreManagementResult failure(String operation, String coreType, String message, String error) {
        CoreManagementResult result = new CoreManagementResult();
        result.setSuccess(false);
        result.setOperation(operation);
        result.setCoreType(coreType);
        result.setMessage(message);
        result.setError(error);
        result.setOperationTime(LocalDateTime.now());
        result.setExitCode(-1);
        return result;
    }
}