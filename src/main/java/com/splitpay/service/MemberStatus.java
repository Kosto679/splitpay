package com.splitpay.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Where a membership stands today.
 *
 * @param paid            the current period (and everything before it) is covered
 * @param overdue         an earlier period than the current one is still unpaid
 * @param periodStart     start date of the current billing period
 * @param periodEnd       end date of the current billing period
 * @param paidThrough     last day covered by payments, or null if nothing is covered yet
 * @param nextBillingDate start of the first period that still needs paying
 * @param monthsAhead     fully covered periods after the current one
 * @param remainingAmount amount still due for unpaid periods (or next period if current is paid)
 */
public record MemberStatus(
        boolean paid,
        boolean overdue,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate paidThrough,
        LocalDate nextBillingDate,
        int monthsAhead,
        BigDecimal remainingAmount,
        int monthsDue) {

    public MemberStatus(
            boolean paid,
            boolean overdue,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate paidThrough,
            LocalDate nextBillingDate,
            int monthsAhead,
            BigDecimal remainingAmount) {
        this(paid, overdue, periodStart, periodEnd, paidThrough, nextBillingDate, monthsAhead, remainingAmount,
                paid ? 0 : 1);
    }

    public MemberStatus(
            boolean paid,
            boolean overdue,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate paidThrough,
            LocalDate nextBillingDate,
            int monthsAhead) {
        this(paid, overdue, periodStart, periodEnd, paidThrough, nextBillingDate, monthsAhead, BigDecimal.ZERO,
                paid ? 0 : 1);
    }
}
