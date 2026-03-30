package com.fun90.airopscat.util;

import com.fun90.airopscat.model.enums.PeriodType;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;

public final class TrafficPeriodUtils {

    private TrafficPeriodUtils() {
    }

    public static LocalDateTime resolveServerPeriodStart(LocalDateTime referenceTime, LocalDateTime resetTime) {
        return resolveMonthlyPeriodStart(referenceTime, resetTime);
    }

    public static LocalDateTime resolveAccountPeriodStart(LocalDateTime referenceTime, LocalDateTime resetTime, String periodType) {
        if (PeriodType.YEARLY.name().equalsIgnoreCase(periodType)) {
            return resolveYearlyPeriodStart(referenceTime, resetTime);
        }
        return resolveMonthlyPeriodStart(referenceTime, resetTime);
    }

    public static LocalDateTime resolvePeriodEnd(LocalDateTime periodStart, String periodType) {
        if (PeriodType.YEARLY.name().equalsIgnoreCase(periodType)) {
            return periodStart.plusYears(1).minusNanos(1);
        }
        return periodStart.plusMonths(1).minusNanos(1);
    }

    private static LocalDateTime resolveMonthlyPeriodStart(LocalDateTime referenceTime, LocalDateTime resetTime) {
        LocalDateTime currentMonthReset = atResetTime(YearMonth.from(referenceTime), resetTime);
        if (!referenceTime.isBefore(currentMonthReset)) {
            return currentMonthReset;
        }
        return atResetTime(YearMonth.from(referenceTime.minusMonths(1)), resetTime);
    }

    private static LocalDateTime resolveYearlyPeriodStart(LocalDateTime referenceTime, LocalDateTime resetTime) {
        LocalDateTime currentYearReset = atResetTime(referenceTime.getYear(), resetTime);
        if (!referenceTime.isBefore(currentYearReset)) {
            return currentYearReset;
        }
        return atResetTime(referenceTime.getYear() - 1, resetTime);
    }

    private static LocalDateTime atResetTime(YearMonth yearMonth, LocalDateTime resetTime) {
        int resetDay = resetTime != null ? resetTime.getDayOfMonth() : 1;
        int day = Math.clamp(resetDay, 1, yearMonth.lengthOfMonth());
        return yearMonth.atDay(day).atTime(resolveResetTimeOfDay(resetTime));
    }

    private static LocalDateTime atResetTime(int year, LocalDateTime resetTime) {
        int month = resetTime != null ? resetTime.getMonthValue() : 1;
        YearMonth yearMonth = YearMonth.of(year, month);
        return atResetTime(yearMonth, resetTime);
    }

    private static LocalTime resolveResetTimeOfDay(LocalDateTime resetTime) {
        return resetTime != null ? resetTime.toLocalTime() : LocalTime.MIDNIGHT;
    }
}
