package com.fun90.airopscat.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH连接配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshConfig {
    
    /**
     * 服务器地址
     */
    @NotBlank(message = "服务器地址不能为空")
    private String host;
    
    /**
     * SSH端口，默认22
     */
    @NotNull(message = "端口不能为空")
    @Min(value = 1, message = "端口必须大于0")
    @Max(value = 65535, message = "端口不能超过65535")
    @Builder.Default
    private Integer port = 22;
    
    /**
     * 用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String username;
    
    /**
     * 密码（密码认证时使用）
     */
    private String password;
    
    /**
     * 私钥文件路径（密钥认证时使用）
     */
    private String privateKeyPath;
    
    /**
     * 私钥字符串内容（当私钥不是文件而是字符串时使用）
     */
    private String privateKeyContent;
    
    /**
     * 私钥密码短语（如果私钥有密码保护）
     */
    private String passphrase;
    
    /**
     * 连接超时时间（毫秒），默认30秒
     */
    @Builder.Default
    private Integer timeout = 30000;
}