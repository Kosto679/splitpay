package com.splitpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

class PeriodsTest {

    @Test
    void periodBeforeBillingDayBelongsToPreviousMonth() {
        YearMonth period = Periods.periodOf(25, LocalDate.of(2026, 9, 10));

        assertThat(period).isEqualTo(YearMonth.of(2026, 8));
        assertThat(Periods.start(period, 25)).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(Periods.end(period, 25)).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void billingDayIsClampedSoEveryMonthHasIt() {
        assertThat(Periods.start(YearMonth.of(2027, 2), 31)).isEqualTo(LocalDate.of(2027, 2, 28));
    }
}
