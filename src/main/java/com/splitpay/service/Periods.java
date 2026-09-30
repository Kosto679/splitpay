package com.splitpay.service;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Billing periods run from the subscription's billing day to the day before the next one.
 * A period is identified by the month it starts in.
 */
public final class Periods {

    private Periods() {
    }

    public static int clampDay(int day) {
        return Math.min(Math.max(day, 1), 28);
    }

    public static YearMonth periodOf(int billingDay, LocalDate date) {
        YearMonth month = YearMonth.from(date);
        return date.getDayOfMonth() >= clampDay(billingDay) ? month : month.minusMonths(1);
    }

    public static LocalDate start(YearMonth period, int billingDay) {
        return period.atDay(clampDay(billingDay));
    }

    public static LocalDate end(YearMonth period, int billingDay) {
        return start(period.plusMonths(1), billingDay).minusDays(1);
    }
}
