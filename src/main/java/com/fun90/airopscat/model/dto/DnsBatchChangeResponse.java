package com.fun90.airopscat.model.dto;

import lombok.Data;

@Data
public class DnsBatchChangeResponse {
    private int deletedCount;
    private int patchedCount;
    private int postedCount;
    private String message;
}
