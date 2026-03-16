package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BackupFileDto {
    private String id;
    private String fileName;
    private String path;
    private long size;
    private LocalDateTime lastModifiedTime;
}
