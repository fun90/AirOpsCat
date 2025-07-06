package com.fun90.airopscat.model.dto;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量命令执行结果
 */
public class BatchCommandResult {
    private final Map<String, CommandResult> results = new ConcurrentHashMap<>();
    
    public void addResult(String host, CommandResult result) {
        results.put(host, result);
    }

    public Map<String, CommandResult> getResults() {
        return results;
    }
}