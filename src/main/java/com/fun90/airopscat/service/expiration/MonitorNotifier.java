package com.fun90.airopscat.service.expiration;

import java.time.LocalDate;
import java.util.List;

public interface MonitorNotifier {
    String getType();

    String getTitle();

    List<String> findItems(LocalDate today);

    default String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return "数量: " + items.size() + "\n" + String.join(", ", items);
    }
}
