package com.fun90.airopscat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertStatePageVo {
    private List<AlertStateVo> records;
    private long total;
    private int pages;
    private int current;
    private int size;
}
