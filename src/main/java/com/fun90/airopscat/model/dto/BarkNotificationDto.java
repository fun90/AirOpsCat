package com.fun90.airopscat.model.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;

/**
 * Bark通知数据传输对象
 * 参考 bark-java-sdk 的 PushRequest 结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BarkNotificationDto {
    
    /**
     * 设备密钥
     */
    @JsonProperty(value = "device_key")
    private String deviceKey;
    
    /**
     * 通知标题
     */
    private String title;
    
    /**
     * 通知内容
     */
    private String body;
    
    /**
     * 通知图标URL
     */
    private String icon;
    
    /**
     * 通知声音
     */
    private String sound;
    
    /**
     * 点击通知后的跳转URL
     */
    private String url;
    
    /**
     * 通知分组
     */
    private String group;
    
    /**
     * 是否自动复制内容到剪贴板
     */
    private Boolean autoCopy;
    
    /**
     * 复制的内容
     */
    private String copy;
    
    /**
     * 通知级别 (active, timeSensitive, passive)
     */
    private String level;
    
    /**
     * 通知徽章数量
     */
    private Integer badge;
    
    /**
     * 通知是否静默
     */
    private Boolean isArchive;
    
    /**
     * 通知分类
     */
    private String category;
    
    /**
     * 通知线程ID
     */
    private String threadId;
    
    /**
     * 通知优先级
     */
    private Integer priority;
    
    /**
     * 通知超时时间（秒）
     */
    private Integer timeout;
    
    /**
     * 通知是否可操作
     */
    private Boolean actionable;
    
    /**
     * 通知操作按钮
     */
    private String actions;
} 