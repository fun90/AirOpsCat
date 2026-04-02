package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemConfigGroupDto {
    private String groupKey;
    private String title;
    private String description;
    private boolean testSupported;
    private int sortOrder;
    private List<SystemConfigItemDto> items;
}
