package com.fun90.airopscat.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrafficPeriodUtilsTest {

    @Test
    void shouldResolveMonthlyPeriodStartingOnDay31AcrossShortMonth() {
        LocalDateTime resetTime = LocalDateTime.of(2026, 1, 31, 0, 0);
        LocalDateTime referenceTime = LocalDateTime.of(2026, 5, 15, 12, 0);

        assertEquals(LocalDateTime.of(2026, 4, 30, 0, 0),
                TrafficPeriodUtils.resolveServerPeriodStart(referenceTime, resetTime));
        assertEquals(LocalDateTime.of(2026, 5, 31, 0, 0),
                TrafficPeriodUtils.resolveServerPeriodEnd(referenceTime, resetTime));
    }

    @Test
    void shouldResolvePeriodAfterDay31Reset() {
        LocalDateTime resetTime = LocalDateTime.of(2026, 1, 31, 0, 0);
        LocalDateTime referenceTime = LocalDateTime.of(2026, 5, 31, 0, 0);

        assertEquals(LocalDateTime.of(2026, 5, 31, 0, 0),
                TrafficPeriodUtils.resolveServerPeriodStart(referenceTime, resetTime));
        assertEquals(LocalDateTime.of(2026, 6, 30, 0, 0),
                TrafficPeriodUtils.resolveServerPeriodEnd(referenceTime, resetTime));
    }

    @Test
    void shouldUseNaturalDayForServerPeriod() {
        LocalDateTime resetTime = LocalDateTime.of(2026, 1, 15, 18, 30);
        LocalDateTime referenceTime = LocalDateTime.of(2026, 7, 15, 8, 0);

        assertEquals(LocalDateTime.of(2026, 7, 15, 0, 0),
                TrafficPeriodUtils.resolveServerPeriodStart(referenceTime, resetTime));
        assertEquals(LocalDateTime.of(2026, 8, 15, 0, 0),
                TrafficPeriodUtils.resolveServerPeriodEnd(referenceTime, resetTime));
    }
}
