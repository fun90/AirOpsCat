package com.fun90.airopscat.service.expiration;

import java.time.LocalDate;
import java.util.List;

public interface ExpiringResourceNotifier {
    String getType();

    String getTitle();

    List<String> findExpiringItems(LocalDate today);

    default String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return "今日到期数量: " + items.size() + "\n" + String.join(", ", items);
    }
}
