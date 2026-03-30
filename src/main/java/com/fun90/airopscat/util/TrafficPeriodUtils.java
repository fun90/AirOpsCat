package com.fun90.airopscat.util;

import com.fun90.airopscat.model.enums.PeriodType;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;

public final class TrafficPeriodUtils {

    private TrafficPeriodUtils() {
    }

    public static LocalDateTime resolveServerPeriodStart(LocalDateTime referenceTime, LocalDateTime resetTime) {
        return resolveMonthlyPeriod(referenceTime, resetTime).start();
    }

    public static LocalDateTime resolveServerPeriodEnd(LocalDateTime referenceTime, LocalDateTime resetTime) {
        return resolveMonthlyPeriod(referenceTime, resetTime).end();
    }

    public static LocalDateTime resolveAccountPeriodStart(LocalDateTime referenceTime, LocalDateTime resetTime, String periodType) {
        if (PeriodType.YEARLY.name().equalsIgnoreCase(periodType)) {
            return resolveYearlyPeriod(referenceTime, resetTime).start();
        }
        return resolveMonthlyPeriod(referenceTime, resetTime).start();
    }

    public static LocalDateTime resolveAccountPeriodEnd(LocalDateTime referenceTime, LocalDateTime resetTime, String periodType) {
        if (PeriodType.YEARLY.name().equalsIgnoreCase(periodType)) {
            return resolveYearlyPeriod(referenceTime, resetTime).end();
        }
        return resolveMonthlyPeriod(referenceTime, resetTime).end();
    }

    private static PeriodBounds resolveMonthlyPeriod(LocalDateTime referenceTime, LocalDateTime resetTime) {
        LocalDateTime currentMonthReset = atResetTime(YearMonth.from(referenceTime), resetTime);
        LocalDateTime periodEnd = referenceTime.isBefore(currentMonthReset)
                ? currentMonthReset.minusNanos(1)
                : atResetTime(YearMonth.from(referenceTime.plusMonths(1)), resetTime).minusNanos(1);
        return new PeriodBounds(periodEnd.minusMonths(1).plusNanos(1), periodEnd);
    }

    private static PeriodBounds resolveYearlyPeriod(LocalDateTime referenceTime, LocalDateTime resetTime) {
        LocalDateTime currentYearReset = atResetTime(referenceTime.getYear(), resetTime);
        LocalDateTime periodEnd = referenceTime.isBefore(currentYearReset)
                ? currentYearReset.minusNanos(1)
                : atResetTime(referenceTime.getYear() + 1, resetTime).minusNanos(1);
        return new PeriodBounds(periodEnd.minusYears(1).plusNanos(1), periodEnd);
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

    private record PeriodBounds(LocalDateTime start, LocalDateTime end) {
    }
}
