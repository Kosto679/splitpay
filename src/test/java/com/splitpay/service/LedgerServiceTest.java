package com.splitpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.splitpay.FixedClockConfig;
import com.splitpay.domain.Membership;
import com.splitpay.domain.Payment;
import com.splitpay.domain.PaymentStatus;
import com.splitpay.domain.Person;
import com.splitpay.domain.Subscription;
import com.splitpay.repo.PersonRepository;
import com.splitpay.repo.SubscriptionRepository;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@Transactional
class LedgerServiceTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    PersonRepository people;

    @Test
    void multipleOfShareIsRecordedAsPaidInAdvance() {
        Membership maria = join(plan("Netflix", "12.00"), person("Maria", "Maria Papa"), "3.00");

        List<Payment> saved = ledger.ingest("You received 9,00 EUR from Maria Papa", "android", ledger.now());

        assertThat(saved).singleElement().satisfies(p -> {
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.MATCHED);
            assertThat(p.getPeriods()).isEqualTo(3);
        });
        MemberStatus status = ledger.status(maria);
        assertThat(status.paid()).isTrue();
        assertThat(status.paidThrough()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(status.nextBillingDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(status.monthsAhead()).isEqualTo(2);
    }

    @Test
    void nextPaymentExtendsTheAdvanceRun() {
        Membership maria = join(plan("Netflix", "12.00"), person("Maria", ""), "3.00");
        ledger.recordFor(maria, draft("9.00"), 3, null);

        Payment next = ledger.recordFor(maria, draft("3.00"), 1, null);

        assertThat(next.getPeriodStart()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(ledger.status(maria).nextBillingDate()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void unpaidMemberIsDueForTheCurrentPeriod() {
        Membership maria = join(plan("Netflix", "12.00"), person("Maria", ""), "3.00");

        MemberStatus status = ledger.status(maria);

        assertThat(status.paid()).isFalse();
        assertThat(status.overdue()).isFalse();
        assertThat(status.paidThrough()).isNull();
        assertThat(status.nextBillingDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void personInSeveralPlansCanPayThemInOneTransfer() {
        Person maria = person("Maria", "ΜΑΡΙΑ ΠΑΠΑ");
        Membership netflix = join(plan("Netflix", "15.00"), maria, "5.00");
        Membership spotify = join(plan("Spotify", "12.00"), maria, "3.00");

        List<Payment> saved = ledger.ingest("Πίστωση 8,00€ από ΠΑΠΑ ΜΑΡΙΑ", "android", ledger.now());

        assertThat(saved).hasSize(2).allMatch(p -> p.getStatus() == PaymentStatus.MATCHED);
        assertThat(ledger.status(netflix).paid()).isTrue();
        assertThat(ledger.status(spotify).paid()).isTrue();
    }

    @Test
    void ambiguousAmountWithoutNameGoesToInbox() {
        Subscription spotify = plan("Spotify", "16.00");
        join(spotify, person("Alex", ""), "8.00");
        join(spotify, person("Nikos", ""), "8.00");

        List<Payment> saved = ledger.ingest("Incoming transfer 8,00 EUR", "android", ledger.now());

        assertThat(saved).singleElement().extracting(Payment::getStatus).isEqualTo(PaymentStatus.UNMATCHED);
    }

    @Test
    void ownerIsAlwaysPaidAndNeverMatched() {
        Subscription netflix = plan("Netflix", "12.00");
        Membership owner = ledger.addMembership(netflix, person("Me", ""), new BigDecimal("3.00"), true);
        Membership maria = join(netflix, person("Maria", ""), "3.00");

        List<Payment> saved = ledger.ingest("Incoming transfer 3,00 EUR", "android", ledger.now());

        assertThat(saved).singleElement().satisfies(p -> assertThat(p.getMembership()).isEqualTo(maria));
        MemberStatus status = ledger.status(owner);
        assertThat(status.paid()).isTrue();
        assertThat(status.nextBillingDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void makingSomeoneOwnerReplacesThePreviousOwner() {
        Subscription netflix = plan("Netflix", "12.00");
        Membership first = ledger.addMembership(netflix, person("Me", ""), new BigDecimal("3.00"), true);
        Membership second = join(netflix, person("Maria", ""), "3.00");

        ledger.setOwner(second, true);

        assertThat(first.isOwner()).isFalse();
        assertThat(second.isOwner()).isTrue();
    }

    @Test
    void explicitFirstMonthIsRespected() {
        Membership maria = join(plan("Netflix", "12.00"), person("Maria", ""), "3.00");

        ledger.recordFor(maria, draft("3.00"), 1, YearMonth.of(2026, 10));

        MemberStatus status = ledger.status(maria);
        assertThat(status.paid()).isFalse();
        assertThat(status.nextBillingDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void lesserAmountSentFromMemberGoesToInboxAndIsNotPaid() {
        Membership maria = join(plan("Netflix", "12.00"), person("Maria", "Maria Papa"), "3.00");

        List<Payment> saved = ledger.ingest("You received 1,50 EUR from Maria Papa", "android", ledger.now());

        assertThat(saved).singleElement().satisfies(p -> {
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.UNMATCHED);
            assertThat(p.getMembership()).isNull();
        });
        MemberStatus status = ledger.status(maria);
        assertThat(status.paid()).isFalse();
    }

    @Test
    void whenAmountGivenIsGreaterThanDue_coversShareForNextMonthAsWell() {
        Membership maria = join(plan("Netflix", "12.00"), person("Maria", "Maria Papa"), "3.00");

        // Transaction doesn't match 3.00, so it lands in inbox for admin review
        List<Payment> saved = ledger.ingest("You received 5,00 EUR from Maria Papa", "android", ledger.now());
        Payment unmatched = saved.getFirst();
        assertThat(unmatched.getStatus()).isEqualTo(PaymentStatus.UNMATCHED);

        // When admin applies/assigns it
        Payment assigned = ledger.assign(unmatched, maria, null);
        assertThat(assigned.getStatus()).isEqualTo(PaymentStatus.MATCHED);
        assertThat(assigned.getPeriods()).isEqualTo(2);

        MemberStatus status = ledger.status(maria);
        // Current month (September) is fully covered
        assertThat(status.paid()).isTrue();
        assertThat(status.paidThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(status.nextBillingDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        // Next month (October) has 2.00 covered towards 3.00, so remaining due for next month is 1.00
        assertThat(ledger.periodPaid(maria, YearMonth.of(2026, 10))).isEqualByComparingTo("2.00");
        assertThat(status.remainingAmount()).isEqualByComparingTo("1.00");
    }

    @Test
    void whenAmountGivenIsLessThanDue_stillShowsRemainingDueAmount() {
        Membership maria = join(plan("Netflix", "12.00"), person("Maria", "Maria Papa"), "3.00");

        // Transaction of 1.50 doesn't match 3.00, so it lands in inbox for admin review
        List<Payment> saved = ledger.ingest("You received 1,50 EUR from Maria Papa", "android", ledger.now());
        Payment unmatched = saved.getFirst();
        assertThat(unmatched.getStatus()).isEqualTo(PaymentStatus.UNMATCHED);

        // When admin applies/assigns it
        Payment assigned = ledger.assign(unmatched, maria, null);
        assertThat(assigned.getStatus()).isEqualTo(PaymentStatus.MATCHED);
        assertThat(assigned.getPeriods()).isEqualTo(1);

        MemberStatus status = ledger.status(maria);
        // Current month is NOT fully paid
        assertThat(status.paid()).isFalse();
        assertThat(status.paidThrough()).isNull();
        assertThat(status.nextBillingDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        // Still shows the remaining due amount of 1.50 EUR
        assertThat(status.remainingAmount()).isEqualByComparingTo("1.50");
        assertThat(ledger.periodPaid(maria, YearMonth.of(2026, 9))).isEqualByComparingTo("1.50");
    }

    @Test
    void secondPaymentAfterUnderpayment_completesMonthAndRollsExcessToNextMonth() {
        Membership maria = join(plan("Netflix", "12.00"), person("Maria", "Maria Papa"), "3.00");

        // First payment: underpayment of 1.50
        Payment p1 = ledger.assign(ledger.ingest("You received 1,50 EUR from Maria Papa", "android", ledger.now()).getFirst(), maria, null);
        MemberStatus status1 = ledger.status(maria);
        assertThat(status1.paid()).isFalse();
        assertThat(status1.remainingAmount()).isEqualByComparingTo("1.50");

        // Second payment: 2.50 from Maria (1.50 completes September, 1.00 goes into October)
        Payment p2 = ledger.assign(ledger.ingest("You received 2,50 EUR from Maria Papa", "android", ledger.now()).getFirst(), maria, null);
        MemberStatus status2 = ledger.status(maria);
        assertThat(status2.paid()).isTrue();
        assertThat(status2.paidThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(status2.nextBillingDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(ledger.periodPaid(maria, YearMonth.of(2026, 9))).isEqualByComparingTo("3.00");
        assertThat(ledger.periodPaid(maria, YearMonth.of(2026, 10))).isEqualByComparingTo("1.00");
        assertThat(status2.remainingAmount()).isEqualByComparingTo("2.00");
    }

    @Test
    void overpaymentSpanningMultipleMonths() {
        Membership maria = join(plan("Family", "50.00"), person("Maria", "Maria Papa"), "10.00");

        Payment p = ledger.assign(ledger.ingest("You received 25,00 EUR from Maria Papa", "android", ledger.now()).getFirst(), maria, null);
        assertThat(p.getPeriods()).isEqualTo(3);

        MemberStatus status = ledger.status(maria);
        assertThat(status.paid()).isTrue();
        // Sep (10.00) and Oct (10.00) fully covered -> 1 month ahead
        assertThat(status.monthsAhead()).isEqualTo(1);
        assertThat(status.paidThrough()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(status.nextBillingDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        // Nov has 5.00 covered out of 10.00 -> 5.00 still due
        assertThat(status.remainingAmount()).isEqualByComparingTo("5.00");
        assertThat(ledger.periodPaid(maria, YearMonth.of(2026, 11))).isEqualByComparingTo("5.00");
    }

    @Test
    void multipleDueMonthsAccumulateAndClearChronologically() {
        // Today in FixedClock is September 2026. Member joined 2 months earlier in July 2026.
        Subscription sub = plan("Family", "50.00");
        Person person = person("Maria", "Maria Papa");
        Membership maria = ledger.addMembership(sub, person, new BigDecimal("10.00"), false,
                LocalDate.of(2026, 7, 1).atStartOfDay());

        MemberStatus initial = ledger.status(maria);
        assertThat(initial.paid()).isFalse();
        assertThat(initial.overdue()).isTrue();
        // July, August, September = 3 months due
        assertThat(initial.monthsDue()).isEqualTo(3);
        assertThat(initial.nextBillingDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(initial.remainingAmount()).isEqualByComparingTo("30.00");

        // Partial payment of 15.00 EUR (clears July 10.00 and half of August 5.00)
        Payment p1 = ledger.assign(ledger.ingest("You received 15,00 EUR from Maria Papa", "android", ledger.now()).getFirst(), maria, null);
        MemberStatus afterP1 = ledger.status(maria);
        assertThat(afterP1.paid()).isFalse();
        assertThat(afterP1.overdue()).isTrue();
        // August (5.00 left) and September (10.00 left) = 2 months due
        assertThat(afterP1.monthsDue()).isEqualTo(2);
        assertThat(afterP1.nextBillingDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(afterP1.paidThrough()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(afterP1.remainingAmount()).isEqualByComparingTo("15.00");

        // Remaining payment of 15.00 EUR completes August and September
        Payment p2 = ledger.assign(ledger.ingest("You received 15,00 EUR from Maria Papa", "android", ledger.now()).getFirst(), maria, null);
        MemberStatus afterP2 = ledger.status(maria);
        assertThat(afterP2.paid()).isTrue();
        assertThat(afterP2.overdue()).isFalse();
        assertThat(afterP2.monthsDue()).isEqualTo(0);
        assertThat(afterP2.paidThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(afterP2.nextBillingDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(afterP2.remainingAmount()).isEqualByComparingTo("10.00");
    }

    private Subscription plan(String name, String total) {
        Subscription sub = new Subscription();
        sub.setName(name);
        sub.setTotalAmount(new BigDecimal(total));
        sub.setBillingDay(1);
        return subscriptions.save(sub);
    }

    private Person person(String name, String aliases) {
        Person person = new Person();
        person.setName(name);
        person.setAliases(aliases);
        person.setCreatedAt(ledger.now());
        return people.save(person);
    }

    private Membership join(Subscription sub, Person person, String share) {
        return ledger.addMembership(sub, person, new BigDecimal(share));
    }

    private PaymentDraft draft(String amount) {
        return new PaymentDraft(new BigDecimal(amount), "EUR", ledger.now(), "manual", "", "");
    }
}
