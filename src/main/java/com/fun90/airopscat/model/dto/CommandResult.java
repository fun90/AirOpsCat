package com.fun90.airopscat.model.dto;

import lombok.Data;

/**
 * 命令执行结果
 */
@Data
public class CommandResult {
    private int exitStatus;
    private String stdout;
    private String stderr;
    public boolean isSuccess() {
        return exitStatus == 0;
    }
}