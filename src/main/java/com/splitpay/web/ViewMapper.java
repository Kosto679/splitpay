package com.splitpay.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.splitpay.domain.Membership;
import com.splitpay.domain.Payment;
import com.splitpay.domain.Person;
import com.splitpay.domain.Subscription;
import com.splitpay.repo.PaymentRepository;
import com.splitpay.service.LedgerService;
import com.splitpay.service.MemberStatus;
import com.splitpay.service.Periods;
import com.splitpay.web.Api.MemberOut;
import com.splitpay.web.Api.MembershipStatusOut;
import com.splitpay.web.Api.PaymentOut;
import com.splitpay.web.Api.PersonOut;
import com.splitpay.web.Api.RecordOut;
import com.splitpay.web.Api.SubscriptionOut;

@Component
public class ViewMapper {

    private final LedgerService ledger;
    private final PaymentRepository payments;

    public ViewMapper(LedgerService ledger, PaymentRepository payments) {
        this.ledger = ledger;
        this.payments = payments;
    }

    public SubscriptionOut subscription(Subscription sub, boolean withPayments) {
        int day = sub.getBillingDay();
        YearMonth current = Periods.periodOf(day, ledger.today());
        List<MemberOut> members = sub.getMemberships().stream()
                .sorted(Comparator.comparing(m -> m.getPerson().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(this::member)
                .toList();
        BigDecimal collected = sub.getMemberships().stream()
                .map(m -> {
                    if (m.isOwner()) {
                        return m.getShareAmount();
                    }
                    BigDecimal paid = ledger.periodPaid(m, current);
                    return paid.min(m.getShareAmount());
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = sub.getTotalAmount().subtract(collected).max(BigDecimal.ZERO);
        List<PaymentOut> recent = withPayments
                ? payments.findTop40BySubscriptionIdOrderByPaidAtDesc(sub.getId()).stream().map(this::payment).toList()
                : List.of();
        return new SubscriptionOut(sub.getId(), sub.getName(), sub.getTotalAmount(), sub.getCurrency(), day,
                sub.getNotes(), Periods.start(current, day), Periods.end(current, day),
                LedgerService.money(collected), LedgerService.money(remaining),
                (int) members.stream().filter(MemberOut::paid).count(), members.size(), members, recent);
    }

    public MemberOut member(Membership m) {
        MemberStatus s = ledger.status(m);
        Person p = m.getPerson();
        Subscription sub = m.getSubscription();
        return new MemberOut(m.getId(), p.getId(), p.getName(), p.getAliases(), m.getShareAmount(),
                sub != null ? sub.getCurrency() : "EUR", m.isOwner(), s.paid(),
                s.overdue(), s.periodStart(), s.periodEnd(), s.paidThrough(), s.nextBillingDate(), s.monthsAhead(),
                s.remainingAmount(), s.monthsDue());
    }

    public MembershipStatusOut membershipStatus(Membership m) {
        MemberStatus s = ledger.status(m);
        Subscription sub = m.getSubscription();
        return new MembershipStatusOut(m.getId(), sub.getId(), sub.getName(), sub.getCurrency(), m.getShareAmount(),
                m.isOwner(), s.paid(), s.overdue(), s.periodStart(), s.periodEnd(), s.paidThrough(), s.nextBillingDate(),
                s.monthsAhead(), s.remainingAmount(), s.monthsDue());
    }

    public List<MembershipStatusOut> membershipStatuses(Person person) {
        return person.getMemberships().stream()
                .sorted(Comparator.comparing(m -> m.getSubscription().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(this::membershipStatus)
                .toList();
    }

    public PersonOut person(Person person) {
        return new PersonOut(person.getId(), person.getName(), person.getAliases(), person.hasPin(),
                membershipStatuses(person), person.getEmail());
    }

    public PaymentOut payment(Payment p) {
        Subscription sub = p.getSubscription();
        Membership m = p.getMembership();
        LocalDate coversUntil = null;
        if (p.getPeriodStart() != null) {
            int day = sub != null ? sub.getBillingDay() : p.getPeriodStart().getDayOfMonth();
            coversUntil = Periods.end(YearMonth.from(p.getPeriodStart()).plusMonths(p.getPeriods() - 1L), day);
        }
        return new PaymentOut(p.getId(), p.getAmount(), p.getCurrency(),
                sub != null ? sub.getId() : null, sub != null ? sub.getName() : null,
                m != null ? m.getId() : null, m != null ? m.getPerson().getName() : null,
                p.getPaidAt(), p.getPeriodStart(), p.getPeriods(), coversUntil, p.getSource(), p.getRawText(),
                p.getPayerHint(), p.getStatus().json());
    }

    public RecordOut record(List<Payment> saved) {
        String status = saved.isEmpty() ? "unmatched" : saved.getFirst().getStatus().json();
        return new RecordOut(status, saved.stream().map(this::payment).toList());
    }
}
