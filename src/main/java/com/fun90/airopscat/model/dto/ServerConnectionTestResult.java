package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerConnectionTestResult {
    private boolean success;
    private String message;

    public static ServerConnectionTestResult success() {
        return new ServerConnectionTestResult(true, "连接成功");
    }

    public static ServerConnectionTestResult failure(String message) {
        return new ServerConnectionTestResult(false, message == null || message.isBlank() ? "连接失败" : message);
    }
}
