package com.fun90.airopscat.util;

import java.time.LocalTime;
import java.util.regex.Pattern;

/** HH:mm 时段判断，起点含、终点不含；终点早于起点表示跨零点 */
public final class TimeWindow {

    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private TimeWindow() {
    }

    public static boolean isHhMm(String value) {
        return value != null && HH_MM.matcher(value).matches();
    }

    /** 起止任一为空或格式非法视为没有时段 */
    public static boolean contains(String start, String end, LocalTime now) {
        if (!isHhMm(start) || !isHhMm(end)) {
            return false;
        }
        LocalTime from = LocalTime.parse(start);
        LocalTime to = LocalTime.parse(end);
        return from.isBefore(to)
                ? !now.isBefore(from) && now.isBefore(to)
                : !now.isBefore(from) || now.isBefore(to);
    }
}
