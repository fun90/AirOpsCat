package com.fun90.airopscat.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DnsBatchChangeRequest {
    private List<DnsBatchChangeItem> deletes = new ArrayList<>();
    private List<DnsBatchChangeItem> patches = new ArrayList<>();
    private List<DnsBatchChangeItem> posts = new ArrayList<>();
}
