package com.splitpay.service;

import java.time.LocalDate;

/**
 * Where a membership stands today.
 *
 * @param paid            the current period (and everything before it) is covered
 * @param overdue         an earlier period than the current one is still unpaid
 * @param paidThrough     last day covered by payments, or null if nothing is covered yet
 * @param nextBillingDate start of the first period that still needs paying
 * @param monthsAhead     fully covered periods after the current one
 */
public record MemberStatus(
        boolean paid,
        boolean overdue,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate paidThrough,
        LocalDate nextBillingDate,
        int monthsAhead) {
}
