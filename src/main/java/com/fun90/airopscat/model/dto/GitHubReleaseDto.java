package com.fun90.airopscat.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GitHub Release API 响应 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubReleaseDto {
    
    /**
     * 版本标签名称 (例如: "v2.0.6")
     */
    @JsonProperty("tag_name")
    private String tagName;
    
    /**
     * 版本名称
     */
    private String name;
    
    /**
     * 版本描述
     */
    private String body;
    
    /**
     * 是否为预发布版本
     */
    @JsonProperty("prerelease")
    private boolean prerelease;
    
    /**
     * 是否为草稿
     */
    private boolean draft;
    
    /**
     * 发布时间
     */
    @JsonProperty("published_at")
    private LocalDateTime publishedAt;
    
    /**
     * HTML URL
     */
    @JsonProperty("html_url")
    private String htmlUrl;
    
    /**
     * 获取版本号 (去除 'v' 前缀)
     */
    public String getVersion() {
        if (tagName != null && tagName.startsWith("v")) {
            return tagName.substring(1);
        }
        return tagName;
    }
}