package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ServerHostDto {
    private Long id;
    private Long serverId;
    private String host;
    private Integer isPrimary;
    private Integer enabled;
    private Integer sort;
    private Long domainId;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
