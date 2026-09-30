package com.splitpay.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** JSON request and response bodies. Serialized in snake_case. */
public final class Api {

    private Api() {
    }

    // ---- requests -----------------------------------------------------------------------

    public record LoginIn(String password) {
    }

    public record SubscriptionIn(
            @NotBlank String name,
            @NotNull @DecimalMin("0.01") BigDecimal totalAmount,
            String currency,
            Integer billingDay,
            String notes) {
    }

    public record MemberIn(Long personId, String name, @NotNull @DecimalMin("0.01") BigDecimal shareAmount,
            Boolean owner) {
    }

    public record OwnerIn(boolean owner) {
    }

    public record ShareIn(@NotNull @DecimalMin("0.01") BigDecimal shareAmount) {
    }

    public record PersonIn(@NotBlank String name, String aliases) {
    }

    public record PinIn(String pin) {
    }

    public record PaymentIn(
            BigDecimal amount,
            String currency,
            Long memberId,
            Integer periods,
            String firstPeriod,
            String paidAt,
            String rawText,
            String source) {
    }

    public record AssignIn(@NotNull Long memberId, Integer periods) {
    }

    public record IngestIn(@NotBlank String text, String source, String paidAt) {
    }

    public record CheckIn(Long personId, String password) {
    }

    // ---- responses ----------------------------------------------------------------------

    public record SessionOut(boolean authed, boolean authRequired, boolean ingestConfigured, String publicUrl) {
    }

    public record MemberOut(
            Long id,
            Long personId,
            String name,
            String aliases,
            BigDecimal shareAmount,
            boolean owner,
            boolean paid,
            boolean overdue,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate paidThrough,
            LocalDate nextBillingDate,
            int monthsAhead) {
    }

    public record SubscriptionOut(
            Long id,
            String name,
            BigDecimal totalAmount,
            String currency,
            int billingDay,
            String notes,
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal collected,
            BigDecimal remaining,
            int paidCount,
            int memberCount,
            List<MemberOut> members,
            List<PaymentOut> payments) {
    }

    public record PaymentOut(
            Long id,
            BigDecimal amount,
            String currency,
            Long subscriptionId,
            String subscriptionName,
            Long memberId,
            String memberName,
            LocalDateTime paidAt,
            LocalDate periodStart,
            int periods,
            LocalDate coversUntil,
            String source,
            String rawText,
            String payerHint,
            String status) {
    }

    public record RecordOut(String status, List<PaymentOut> payments) {
    }

    public record DashboardOut(List<SubscriptionOut> subscriptions, long inboxCount) {
    }

    public record MembershipStatusOut(
            Long memberId,
            Long subscriptionId,
            String subscriptionName,
            String currency,
            BigDecimal shareAmount,
            boolean owner,
            boolean paid,
            boolean overdue,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate paidThrough,
            LocalDate nextBillingDate,
            int monthsAhead) {
    }

    public record PersonOut(Long id, String name, String aliases, boolean hasPin, List<MembershipStatusOut> memberships) {
    }

    public record PinOut(String pin) {
    }

    public record InboxMemberOut(Long id, String personName, String subscriptionName, String currency,
            BigDecimal shareAmount) {
    }

    public record InboxOut(List<PaymentOut> payments, List<InboxMemberOut> members) {
    }

    public record ParseOut(BigDecimal amount, String currency, String payerHint) {
    }

    public record PublicPersonOut(Long id, String name) {
    }

    public record PublicStatusOut(String name, LocalDate today, List<MembershipStatusOut> subscriptions) {
    }
}
