package com.fun90.airopscat.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountTransactionDescriptionUtilsTest {

    @Test
    void shouldBuildConsistentInitialAndRenewalDescriptions() {
        LocalDateTime fromDate = LocalDateTime.of(2026, 7, 16, 10, 0);
        LocalDateTime toDate = LocalDateTime.of(2026, 10, 16, 10, 0);

        assertEquals(3, AccountTransactionDescriptionUtils.calculateMonths(fromDate, toDate));
        assertEquals("账号首期：3月", AccountTransactionDescriptionUtils.initialDescription(fromDate, toDate));
        assertEquals("账号：3月", AccountTransactionDescriptionUtils.renewalDescription(fromDate, toDate));
    }

    @Test
    void shouldUseAtLeastOneMonthForShortPeriod() {
        LocalDateTime fromDate = LocalDateTime.of(2026, 7, 16, 10, 0);
        LocalDateTime toDate = fromDate.plusDays(7);

        assertEquals(1, AccountTransactionDescriptionUtils.calculateMonths(fromDate, toDate));
    }
}
