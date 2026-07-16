package com.fun90.airopscat.util;

import java.time.LocalDateTime;
import java.time.YearMonth;

public final class AccountTransactionDescriptionUtils {
    private AccountTransactionDescriptionUtils() {
    }

    public static int calculateMonths(LocalDateTime fromDate, LocalDateTime toDate) {
        if (fromDate == null || toDate == null || !toDate.isAfter(fromDate)) {
            return 1;
        }

        YearMonth fromMonth = YearMonth.from(fromDate);
        YearMonth toMonth = YearMonth.from(toDate);
        int months = (toMonth.getYear() - fromMonth.getYear()) * 12
                + toMonth.getMonthValue() - fromMonth.getMonthValue();
        return Math.max(1, months);
    }

    public static String renewalDescription(LocalDateTime fromDate, LocalDateTime toDate) {
        return "账号：" + calculateMonths(fromDate, toDate) + "月";
    }

    public static String initialDescription(LocalDateTime fromDate, LocalDateTime toDate) {
        return "账号首期：" + calculateMonths(fromDate, toDate) + "月";
    }
}
