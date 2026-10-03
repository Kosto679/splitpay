package com.splitpay.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.splitpay.domain.Membership;
import com.splitpay.domain.Payment;
import com.splitpay.domain.PaymentStatus;
import com.splitpay.domain.Person;
import com.splitpay.domain.Subscription;
import com.splitpay.repo.MembershipRepository;
import com.splitpay.repo.PaymentRepository;
import com.splitpay.repo.PersonRepository;
import com.splitpay.repo.SubscriptionRepository;
import com.splitpay.service.NotificationParser.ParsedNotice;

@Service
@Transactional
public class LedgerService {

    public static final int MAX_PERIODS = 24;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.05");

    private final MembershipRepository memberships;
    private final PaymentRepository payments;
    private final PersonRepository people;
    private final SubscriptionRepository subscriptions;
    private final NotificationParser parser;
    private final Clock clock;

    public LedgerService(MembershipRepository memberships, PaymentRepository payments, PersonRepository people,
            SubscriptionRepository subscriptions, NotificationParser parser, Clock clock) {
        this.memberships = memberships;
        this.payments = payments;
        this.people = people;
        this.subscriptions = subscriptions;
        this.parser = parser;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public LocalDateTime resolvePaidAt(String value) {
        if (value == null || value.isBlank()) {
            return now();
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(clock.getZone()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through to local formats
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through to date only
        }
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date: " + value);
        }
    }

    // ---- status -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MemberStatus status(Membership membership) {
        int day = membership.getSubscription().getBillingDay();
        YearMonth current = Periods.periodOf(day, today());
        if (membership.isOwner()) {
            return new MemberStatus(true, false, Periods.start(current, day), Periods.end(current, day),
                    Periods.end(current, day), Periods.start(current.plusMonths(1), day), 0, BigDecimal.ZERO, 0);
        }
        Map<YearMonth, BigDecimal> periodPaid = periodPayments(membership);
        BigDecimal share = membership.getShareAmount();
        YearMonth first = trackingStart(membership);
        YearMonth open = firstOpen(periodPaid, share, first);
        boolean paid = open.isAfter(current);
        LocalDate paidThrough = open.isAfter(first) ? Periods.start(open, day).minusDays(1) : null;
        int ahead = paid ? (int) ChronoUnit.MONTHS.between(current, open) - 1 : 0;
        int monthsDue = paid ? 0 : (int) ChronoUnit.MONTHS.between(open, current) + 1;

        BigDecimal remainingAmount;
        if (!paid) {
            BigDecimal totalDue = BigDecimal.ZERO;
            YearMonth p = open;
            while (!p.isAfter(current)) {
                BigDecimal paidAmount = periodPaid.getOrDefault(p, BigDecimal.ZERO);
                BigDecimal due = share.subtract(paidAmount).max(BigDecimal.ZERO);
                totalDue = totalDue.add(due);
                p = p.plusMonths(1);
            }
            remainingAmount = money(totalDue);
        } else {
            BigDecimal paidOnOpen = periodPaid.getOrDefault(open, BigDecimal.ZERO);
            remainingAmount = money(share.subtract(paidOnOpen).max(BigDecimal.ZERO));
        }

        return new MemberStatus(paid, open.isBefore(current), Periods.start(current, day), Periods.end(current, day),
                paidThrough, Periods.start(open, day), ahead, remainingAmount, monthsDue);
    }

    @Transactional(readOnly = true)
    public Map<YearMonth, BigDecimal> periodPayments(Membership membership) {
        Map<YearMonth, BigDecimal> periodPaid = new HashMap<>();
        if (membership == null || membership.getId() == null) {
            return periodPaid;
        }
        BigDecimal share = membership.getShareAmount();
        if (share == null || share.signum() <= 0) {
            return periodPaid;
        }
        List<Payment> matched = payments.findByMembershipIdAndStatus(membership.getId(), PaymentStatus.MATCHED).stream()
                .sorted(Comparator.comparing(Payment::getPaidAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Payment::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        YearMonth trackingStart = trackingStart(membership);

        for (Payment payment : matched) {
            if (payment.getAmount() == null || payment.getAmount().signum() <= 0) {
                continue;
            }
            YearMonth period = payment.getPeriodStart() != null
                    ? YearMonth.from(payment.getPeriodStart())
                    : firstOpen(periodPaid, share, trackingStart);
            BigDecimal unallocated = payment.getAmount();
            int iterations = 0;
            while (unallocated.compareTo(new BigDecimal("0.005")) > 0 && iterations++ < 240) {
                BigDecimal alreadyPaid = periodPaid.getOrDefault(period, BigDecimal.ZERO);
                BigDecimal needed = share.subtract(alreadyPaid);
                if (needed.compareTo(BigDecimal.ZERO) <= 0) {
                    period = period.plusMonths(1);
                    continue;
                }
                BigDecimal contribute = unallocated.min(needed);
                periodPaid.put(period, money(alreadyPaid.add(contribute)));
                unallocated = unallocated.subtract(contribute);
                if (unallocated.compareTo(new BigDecimal("0.005")) > 0) {
                    period = period.plusMonths(1);
                }
            }
        }
        return periodPaid;
    }

    @Transactional(readOnly = true)
    public BigDecimal periodPaid(Membership membership, YearMonth period) {
        if (membership == null || membership.isOwner()) {
            return membership != null ? membership.getShareAmount() : BigDecimal.ZERO;
        }
        return periodPayments(membership).getOrDefault(period, BigDecimal.ZERO);
    }

    /** Earliest period the person owes: when they joined, or earlier if a payment was booked before that. */
    YearMonth trackingStart(Membership membership) {
        int day = membership.getSubscription() != null ? membership.getSubscription().getBillingDay() : 1;
        LocalDate joinDate = membership.getCreatedAt() != null ? membership.getCreatedAt().toLocalDate() : today();
        YearMonth joined = Periods.periodOf(day, joinDate);
        if (membership.getId() == null) {
            return joined;
        }
        return payments.findByMembershipIdAndStatus(membership.getId(), PaymentStatus.MATCHED).stream()
                .filter(p -> p.getPeriodStart() != null)
                .map(p -> YearMonth.from(p.getPeriodStart()))
                .min(Comparator.naturalOrder())
                .filter(p -> p.isBefore(joined))
                .orElse(joined);
    }

    public YearMonth firstOpen(Map<YearMonth, BigDecimal> periodPaid, BigDecimal share, YearMonth from) {
        YearMonth period = from;
        int count = 0;
        while (isFullyCovered(periodPaid.get(period), share) && count++ < 240) {
            period = period.plusMonths(1);
        }
        return period;
    }

    private static boolean isFullyCovered(BigDecimal paid, BigDecimal share) {
        if (paid == null) {
            return false;
        }
        return paid.add(TOLERANCE).compareTo(share) >= 0;
    }

    public int calculatePeriods(BigDecimal amount, Membership membership, YearMonth firstPeriod) {
        if (amount == null || amount.signum() <= 0 || membership == null) {
            return 1;
        }
        BigDecimal share = membership.getShareAmount();
        if (share == null || share.signum() <= 0) {
            return 1;
        }
        Map<YearMonth, BigDecimal> periodPaid = periodPayments(membership);
        YearMonth period = firstPeriod != null ? firstPeriod : firstOpen(periodPaid, share, trackingStart(membership));
        BigDecimal unallocated = amount;
        int periodsTouched = 0;
        int iterations = 0;
        while (unallocated.compareTo(new BigDecimal("0.005")) > 0 && iterations++ < MAX_PERIODS) {
            periodsTouched++;
            BigDecimal alreadyPaid = periodPaid.getOrDefault(period, BigDecimal.ZERO);
            BigDecimal needed = share.subtract(alreadyPaid);
            if (needed.compareTo(BigDecimal.ZERO) <= 0) {
                period = period.plusMonths(1);
                continue;
            }
            BigDecimal contribute = unallocated.min(needed);
            unallocated = unallocated.subtract(contribute);
            period = period.plusMonths(1);
        }
        return Math.clamp(Math.max(1, periodsTouched), 1, MAX_PERIODS);
    }

    public Optional<Integer> periodsFor(BigDecimal amount, BigDecimal share, int max) {
        if (amount == null || share == null || share.signum() <= 0) {
            return Optional.empty();
        }
        for (int n = 1; n <= max; n++) {
            BigDecimal expected = share.multiply(BigDecimal.valueOf(n));
            if (close(amount, expected)) {
                return Optional.of(n);
            }
            if (expected.compareTo(amount.add(TOLERANCE)) > 0) {
                break;
            }
        }
        return Optional.empty();
    }

    private static boolean close(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(TOLERANCE) <= 0;
    }

    // ---- matching -----------------------------------------------------------------------

    /**
     * Memberships a transfer should be credited to. More than one result means the transfer
     * covers every subscription of one person at once, one period each.
     */
    @Transactional(readOnly = true)
    public List<Membership> matchTargets(BigDecimal amount, String payerHint, String rawText) {
        List<Membership> all = memberships.findAll().stream().filter(m -> !m.isOwner()).toList();
        List<Membership> named = all.stream().filter(m -> nameMatches(m.getPerson(), payerHint)).toList();
        if (named.isEmpty() && rawText != null) {
            Set<String> textWords = words(rawText);
            named = all.stream().filter(m -> mentionedIn(m.getPerson(), textWords)).toList();
        }
        if (named.isEmpty()) {
            return pickOne(all.stream().filter(m -> periodsFor(amount, m.getShareAmount(), 1).isPresent()).toList());
        }
        List<Membership> fits = named.stream()
                .filter(m -> periodsFor(amount, m.getShareAmount(), MAX_PERIODS).isPresent())
                .toList();
        if (!fits.isEmpty()) {
            return pickOne(fits);
        }
        long distinctPeople = named.stream().map(m -> m.getPerson().getId()).distinct().count();
        if (distinctPeople != 1) {
            return List.of();
        }
        BigDecimal total = named.stream().map(Membership::getShareAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return close(amount, total) ? named : List.of();
    }

    private List<Membership> pickOne(List<Membership> candidates) {
        if (candidates.size() == 1) {
            return candidates;
        }
        List<Membership> unpaid = candidates.stream().filter(m -> !status(m).paid()).toList();
        return unpaid.size() == 1 ? unpaid : List.of();
    }

    static boolean nameMatches(Person person, String hint) {
        Set<String> hintWords = words(hint);
        if (hintWords.isEmpty()) {
            return false;
        }
        for (Set<String> candidate : candidateNames(person)) {
            if (hintWords.containsAll(candidate) || candidate.containsAll(hintWords)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionedIn(Person person, Set<String> textWords) {
        return candidateNames(person).stream().anyMatch(textWords::containsAll);
    }

    private static List<Set<String>> candidateNames(Person person) {
        List<String> raw = new ArrayList<>();
        raw.add(person.getName());
        raw.addAll(Arrays.asList(person.getAliases().split(",")));
        return raw.stream().map(LedgerService::words).filter(w -> !w.isEmpty()).toList();
    }

    private static Set<String> words(String value) {
        String normalized = NotificationParser.normalizeName(value);
        return normalized.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(normalized.split(" ")));
    }

    // ---- recording ----------------------------------------------------------------------

    public List<Payment> ingest(String text, String source, LocalDateTime paidAt) {
        ParsedNotice notice = parser.parse(text);
        if (notice.amount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not find an amount in that notification");
        }
        return recordAuto(new PaymentDraft(notice.amount(), notice.currency(), paidAt, source, text, notice.payerHint()));
    }

    public List<Payment> recordAuto(PaymentDraft draft) {
        List<Membership> targets = matchTargets(draft.amount(), draft.payerHint(), draft.rawText());
        if (targets.isEmpty()) {
            Payment payment = newPayment(draft);
            payment.setStatus(PaymentStatus.UNMATCHED);
            return List.of(payments.save(payment));
        }
        if (targets.size() == 1) {
            Membership membership = targets.getFirst();
            int periods = periodsFor(draft.amount(), membership.getShareAmount(), MAX_PERIODS).orElse(1);
            return List.of(recordFor(membership, draft, periods, null));
        }
        return targets.stream()
                .map(m -> recordFor(m, draft.withAmount(m.getShareAmount()), 1, null))
                .toList();
    }

    /** Credits a payment to a membership. With no first period, it fills the earliest unpaid one. */
    public Payment recordFor(Membership membership, PaymentDraft draft, int periods, YearMonth firstPeriod) {
        Payment payment = newPayment(draft);
        int count = periods > 0 ? periods : calculatePeriods(draft.amount(), membership, firstPeriod);
        credit(payment, membership, count, firstPeriod);
        return payments.save(payment);
    }

    public Payment assign(Payment payment, Membership membership, Integer periods) {
        payment.setStatus(PaymentStatus.UNMATCHED);
        payment.setMembership(null);
        payments.saveAndFlush(payment);
        int count = periods != null
                ? Math.clamp(periods, 1, MAX_PERIODS)
                : calculatePeriods(payment.getAmount(), membership, null);
        credit(payment, membership, count, null);
        return payments.save(payment);
    }

    private void credit(Payment payment, Membership membership, int periods, YearMonth firstPeriod) {
        int day = membership.getSubscription().getBillingDay();
        Map<YearMonth, BigDecimal> periodPaid = periodPayments(membership);
        YearMonth start = firstPeriod != null ? firstPeriod
                : firstOpen(periodPaid, membership.getShareAmount(), trackingStart(membership));
        payment.setMembership(membership);
        payment.setSubscription(membership.getSubscription());
        payment.setPeriodStart(Periods.start(start, day));
        payment.setPeriods(Math.clamp(periods, 1, MAX_PERIODS));
        payment.setStatus(PaymentStatus.MATCHED);
    }

    private Payment newPayment(PaymentDraft draft) {
        Payment payment = new Payment();
        payment.setAmount(money(draft.amount()));
        payment.setCurrency(draft.currency() == null || draft.currency().isBlank() ? "EUR" : draft.currency());
        payment.setPaidAt(draft.paidAt() != null ? draft.paidAt() : now());
        payment.setSource(truncate(draft.source() == null || draft.source().isBlank() ? "manual" : draft.source(), 32));
        payment.setRawText(truncate(draft.rawText(), 4000));
        payment.setPayerHint(truncate(draft.payerHint(), 160));
        return payment;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ---- lifecycle ----------------------------------------------------------------------

    public Membership addMembership(Subscription subscription, Person person, BigDecimal share) {
        return addMembership(subscription, person, share, false);
    }

    public Membership addMembership(Subscription subscription, Person person, BigDecimal share, boolean owner) {
        return addMembership(subscription, person, share, owner, now());
    }

    public Membership addMembership(Subscription subscription, Person person, BigDecimal share, boolean owner, LocalDateTime startAt) {
        Membership membership = new Membership();
        membership.setSubscription(subscription);
        membership.setPerson(person);
        membership.setShareAmount(money(share));
        membership.setCreatedAt(startAt != null ? startAt : now());
        subscription.getMemberships().add(membership);
        person.getMemberships().add(membership);
        Membership saved = memberships.save(membership);
        if (owner) {
            setOwner(saved, true);
        }
        return saved;
    }

    /** A subscription has at most one owner; making someone owner clears the previous one. */
    public void setOwner(Membership membership, boolean owner) {
        if (owner) {
            membership.getSubscription().getMemberships().forEach(m -> m.setOwner(false));
        }
        membership.setOwner(owner);
        memberships.saveAll(membership.getSubscription().getMemberships());
    }

    public void removeMembership(Membership membership) {
        payments.findByMembershipId(membership.getId()).forEach(p -> p.setMembership(null));
        membership.getSubscription().getMemberships().remove(membership);
        membership.getPerson().getMemberships().remove(membership);
        memberships.delete(membership);
    }

    public void deletePerson(Person person) {
        new ArrayList<>(person.getMemberships()).forEach(this::removeMembership);
        people.delete(person);
    }

    public void deleteSubscription(Subscription subscription) {
        payments.findBySubscriptionId(subscription.getId()).forEach(p -> {
            p.setSubscription(null);
            p.setMembership(null);
        });
        subscription.getMemberships().forEach(m -> m.getPerson().getMemberships().remove(m));
        subscriptions.delete(subscription);
    }
}
