package com.fun90.airopscat.model.dto;

import com.fun90.airopscat.model.entity.SystemLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemLogPageVo {
    private List<SystemLog> records;
    private long total;
    private int pages;
    private int current;
    private int size;
}
