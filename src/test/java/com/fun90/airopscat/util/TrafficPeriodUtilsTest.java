package com.fun90.airopscat.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrafficPeriodUtilsTest {

    @Test
    void shouldKeepMonthlyPeriodAnchoredToResetDayWhenReferenceIsBeforeCurrentMonthReset() {
        LocalDateTime resetTime = LocalDateTime.of(2026, 3, 30, 12, 51, 44);
        LocalDateTime referenceTime = LocalDateTime.of(2026, 3, 29, 10, 0);

        assertEquals(
                LocalDateTime.of(2026, 2, 28, 12, 51, 44),
                TrafficPeriodUtils.resolveServerPeriodStart(referenceTime, resetTime)
        );
        assertEquals(
                LocalDateTime.of(2026, 3, 30, 12, 51, 43, 999_999_999),
                TrafficPeriodUtils.resolveServerPeriodEnd(referenceTime, resetTime)
        );
    }

    @Test
    void shouldMoveToNextMonthlyPeriodAfterResetTime() {
        LocalDateTime resetTime = LocalDateTime.of(2026, 3, 30, 12, 51, 44);
        LocalDateTime referenceTime = LocalDateTime.of(2026, 3, 30, 12, 51, 45);

        assertEquals(
                LocalDateTime.of(2026, 3, 30, 12, 51, 44),
                TrafficPeriodUtils.resolveServerPeriodStart(referenceTime, resetTime)
        );
        assertEquals(
                LocalDateTime.of(2026, 4, 30, 12, 51, 43, 999_999_999),
                TrafficPeriodUtils.resolveServerPeriodEnd(referenceTime, resetTime)
        );
    }
}
