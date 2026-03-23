package com.fun90.airopscat.model.vo;

import java.util.List;

public record ConsoleMenuGroup(
        String key,
        String title,
        String iconKey,
        int order,
        List<ConsoleMenuItem> items
) {
}
