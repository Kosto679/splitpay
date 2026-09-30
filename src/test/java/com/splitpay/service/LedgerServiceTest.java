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
