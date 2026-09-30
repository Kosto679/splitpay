package com.splitpay.web;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.splitpay.domain.Membership;
import com.splitpay.domain.Payment;
import com.splitpay.domain.PaymentStatus;
import com.splitpay.repo.MembershipRepository;
import com.splitpay.repo.PaymentRepository;
import com.splitpay.service.LedgerService;
import com.splitpay.service.NotificationParser;
import com.splitpay.service.NotificationParser.ParsedNotice;
import com.splitpay.service.PaymentDraft;
import com.splitpay.web.Api.AssignIn;
import com.splitpay.web.Api.InboxMemberOut;
import com.splitpay.web.Api.InboxOut;
import com.splitpay.web.Api.ParseOut;
import com.splitpay.web.Api.PaymentIn;
import com.splitpay.web.Api.PaymentOut;
import com.splitpay.web.Api.RecordOut;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentRepository payments;
    private final MembershipRepository memberships;
    private final LedgerService ledger;
    private final NotificationParser parser;
    private final ViewMapper views;

    public PaymentController(PaymentRepository payments, MembershipRepository memberships, LedgerService ledger,
            NotificationParser parser, ViewMapper views) {
        this.payments = payments;
        this.memberships = memberships;
        this.ledger = ledger;
        this.parser = parser;
        this.views = views;
    }

    @GetMapping("/inbox")
    InboxOut inbox() {
        List<PaymentOut> waiting = payments.findByStatusOrderByPaidAtDesc(PaymentStatus.UNMATCHED).stream()
                .map(views::payment)
                .toList();
        List<InboxMemberOut> members = memberships.findAll().stream()
                .filter(m -> !m.isOwner())
                .sorted(Comparator.comparing((Membership m) -> m.getPerson().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(m -> m.getSubscription().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(m -> new InboxMemberOut(m.getId(), m.getPerson().getName(), m.getSubscription().getName(),
                        m.getSubscription().getCurrency(), m.getShareAmount()))
                .toList();
        return new InboxOut(waiting, members);
    }

    @PostMapping("/payments")
    RecordOut create(@RequestBody PaymentIn in) {
        String rawText = in.rawText() == null ? "" : in.rawText().strip();
        ParsedNotice notice = rawText.isEmpty() ? null : parser.parse(rawText);
        String payerHint = notice == null ? "" : notice.payerHint();
        String source = in.source() == null || in.source().isBlank() ? "manual" : in.source();

        if (in.memberId() != null) {
            Membership membership = findMembership(in.memberId());
            BigDecimal share = membership.getShareAmount();
            BigDecimal given = in.amount() != null ? in.amount() : notice == null ? null : notice.amount();
            int periods = in.periods() != null
                    ? Math.clamp(in.periods(), 1, LedgerService.MAX_PERIODS)
                    : given == null ? 1 : ledger.periodsFor(given, share, LedgerService.MAX_PERIODS).orElse(1);
            BigDecimal amount = given != null ? given : share.multiply(BigDecimal.valueOf(periods));
            String currency = currencyOr(in.currency(), membership.getSubscription().getCurrency());
            PaymentDraft draft = new PaymentDraft(amount, currency, ledger.resolvePaidAt(in.paidAt()), source,
                    rawText, payerHint);
            Payment saved = ledger.recordFor(membership, draft, periods, firstPeriod(in.firstPeriod()));
            return views.record(List.of(saved));
        }

        BigDecimal amount = in.amount() != null ? in.amount() : notice == null ? null : notice.amount();
        if (amount == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not find an amount");
        }
        String currency = currencyOr(in.currency(), notice == null ? "EUR" : notice.currency());
        PaymentDraft draft = new PaymentDraft(amount, currency, ledger.resolvePaidAt(in.paidAt()), source, rawText,
                payerHint);
        return views.record(ledger.recordAuto(draft));
    }

    @PostMapping("/payments/{id}/assign")
    PaymentOut assign(@PathVariable Long id, @Valid @RequestBody AssignIn in) {
        Payment payment = find(id);
        return views.payment(ledger.assign(payment, findMembership(in.memberId()), in.periods()));
    }

    @PostMapping("/payments/{id}/ignore")
    Map<String, Boolean> ignore(@PathVariable Long id) {
        Payment payment = find(id);
        payment.setStatus(PaymentStatus.IGNORED);
        payments.save(payment);
        return Map.of("ok", true);
    }

    @DeleteMapping("/payments/{id}")
    Map<String, Boolean> delete(@PathVariable Long id) {
        payments.delete(find(id));
        return Map.of("ok", true);
    }

    @GetMapping("/parse")
    ParseOut parse(@RequestParam String text) {
        ParsedNotice notice = parser.parse(text);
        return new ParseOut(notice.amount(), notice.currency(), notice.payerHint());
    }

    private static String currencyOr(String requested, String fallback) {
        return requested == null || requested.isBlank() ? fallback : requested.strip().toUpperCase(Locale.ROOT);
    }

    private static YearMonth firstPeriod(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "First month must look like 2026-10");
        }
    }

    private Payment find(Long id) {
        return payments.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    private Membership findMembership(Long id) {
        return memberships.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
    }
}
