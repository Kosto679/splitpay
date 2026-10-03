package com.splitpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.splitpay.FixedClockConfig;
import com.splitpay.SplitpayProperties;
import com.splitpay.domain.Membership;
import com.splitpay.domain.Person;
import com.splitpay.domain.Subscription;
import com.splitpay.repo.MembershipRepository;
import com.splitpay.repo.PersonRepository;
import com.splitpay.repo.SubscriptionRepository;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@Transactional
class NotificationServiceTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    PersonRepository people;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    SubscriptionRepository subscriptions;

    JavaMailSender mailer;
    NotificationService notifications;
    SplitpayProperties props;
    Clock clock;

    @BeforeEach
    void setUp() {
        mailer = mock(JavaMailSender.class);
        when(mailer.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));

        props = new SplitpayProperties(
                "secret", "token", "https://splitpay.example.com", "Europe/Athens",
                "notifications@splitpay.example.com", "support@splitpay.example.com");

        clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneId.of("Europe/Athens"));

        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailer);

        notifications = new NotificationService(provider, props, ledger, people, memberships, clock);
    }

    @Test
    void isConfiguredReturnsTrueWhenMailerAndFromAddressAreSet() {
        assertThat(notifications.isConfigured()).isTrue();

        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        NotificationService unconfigured = new NotificationService(
                emptyProvider, props, ledger, people, memberships, clock);
        assertThat(unconfigured.isConfigured()).isFalse();
    }

    @Test
    void sendWelcomeEmailRendersHtmlAndSendsMessage() throws Exception {
        Person maria = new Person();
        maria.setName("Maria");
        maria.setEmail("maria@example.com");
        maria = people.save(maria);

        boolean sent = notifications.sendWelcomeEmail(maria, "pass123");

        assertThat(sent).isTrue();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailer).send(captor.capture());

        MimeMessage msg = captor.getValue();
        assertThat(msg.getAllRecipients()[0].toString()).isEqualTo("maria@example.com");
        assertThat(msg.getSubject()).isEqualTo("Welcome to Splitpay!");

        String content = (String) msg.getContent();
        assertThat(content).contains("Hi Maria,");
        assertThat(content).contains("https://splitpay.example.com/check");
        assertThat(content).contains("pass123");
        assertThat(content).contains("support@splitpay.example.com");
    }

    @Test
    void sendPasswordResetEmailRendersHtmlAndSendsMessage() throws Exception {
        Person maria = new Person();
        maria.setName("Maria");
        maria.setEmail("maria@example.com");
        maria = people.save(maria);

        boolean sent = notifications.sendPasswordResetEmail(maria, "new-pass-888");

        assertThat(sent).isTrue();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailer).send(captor.capture());

        MimeMessage msg = captor.getValue();
        assertThat(msg.getAllRecipients()[0].toString()).isEqualTo("maria@example.com");
        assertThat(msg.getSubject()).isEqualTo("Splitpay: Your check-in password has been reset");

        String content = (String) msg.getContent();
        assertThat(content).contains("Hi Maria,");
        assertThat(content).contains("new-pass-888");
        assertThat(content).contains("https://splitpay.example.com/check");
        assertThat(content).contains("previous password no longer works");
        assertThat(content).contains("support@splitpay.example.com");
    }

    @Test
    void sendCheckInPasswordEmailRendersHtmlAndSendsMessage() throws Exception {
        Person john = new Person();
        john.setName("John");
        john.setEmail("john@example.com");
        john = people.save(john);

        boolean sent = notifications.sendCheckInPasswordEmail(john, "john-pass-123");

        assertThat(sent).isTrue();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailer).send(captor.capture());

        MimeMessage msg = captor.getValue();
        assertThat(msg.getAllRecipients()[0].toString()).isEqualTo("john@example.com");
        assertThat(msg.getSubject()).isEqualTo("Splitpay: Your check-in password is ready");

        String content = (String) msg.getContent();
        assertThat(content).contains("Hi John,");
        assertThat(content).contains("john-pass-123");
        assertThat(content).contains("https://splitpay.example.com/check");
        assertThat(content).contains("support@splitpay.example.com");
    }

    @Test
    void sendPaymentReminderSkipsWhenNoUnpaidDues() {
        Person maria = new Person();
        maria.setName("Maria");
        maria.setEmail("maria@example.com");
        maria = people.save(maria);

        // Maria has no memberships
        boolean sent = notifications.sendPaymentReminder(maria, true);
        assertThat(sent).isFalse();
        verify(mailer, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendPaymentReminderSendsEmailForUnpaidMembershipsAndEnforcesCooldown() throws Exception {
        Person maria = new Person();
        maria.setName("Maria");
        maria.setEmail("maria@example.com");
        maria.setCreatedAt(ledger.now().minusMonths(1));
        maria = people.save(maria);

        Subscription netflix = new Subscription();
        netflix.setName("Netflix");
        netflix.setTotalAmount(new BigDecimal("12.00"));
        netflix.setBillingDay(1);
        netflix = subscriptions.save(netflix);

        Membership mem = new Membership();
        mem.setPerson(maria);
        mem.setSubscription(netflix);
        mem.setShareAmount(new BigDecimal("3.00"));
        mem.setOwner(false);
        mem.setCreatedAt(ledger.now().minusMonths(1));
        mem = memberships.save(mem);

        // Send initial reminder
        boolean sentFirst = notifications.sendPaymentReminder(maria, false);
        assertThat(sentFirst).isTrue();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailer, times(1)).send(captor.capture());

        MimeMessage msg = captor.getValue();
        assertThat(msg.getAllRecipients()[0].toString()).isEqualTo("maria@example.com");
        assertThat(msg.getSubject()).contains("Splitpay: Payment Reminder");

        String content = (String) msg.getContent();
        assertThat(content).contains("Hi Maria,");
        assertThat(content).contains("Netflix");
        assertThat(content).contains("6.00 EUR");
        assertThat(content).contains("https://splitpay.example.com/check");

        // Verify emailReminderSentAt was updated on the membership
        Membership updated = memberships.findById(mem.getId()).orElseThrow();
        assertThat(updated.getEmailReminderSentAt()).isNotNull();

        // Sending again with force=false should be skipped by cooldown
        boolean sentAgainWithoutForce = notifications.sendPaymentReminder(maria, false);
        assertThat(sentAgainWithoutForce).isFalse();
        verify(mailer, times(1)).send(any(MimeMessage.class)); // still only 1 call

        // Sending with force=true should bypass cooldown
        boolean sentWithForce = notifications.sendPaymentReminder(maria, true);
        assertThat(sentWithForce).isTrue();
        verify(mailer, times(2)).send(any(MimeMessage.class));
    }

    @Test
    void sendAllOverdueRemindersNotifiesAllEligiblePeople() {
        Person maria = new Person();
        maria.setName("Maria");
        maria.setEmail("maria@example.com");
        maria.setCreatedAt(ledger.now().minusMonths(1));
        maria = people.save(maria);

        Person john = new Person();
        john.setName("John");
        john.setEmail("john@example.com");
        john.setCreatedAt(ledger.now().minusMonths(1));
        john = people.save(john);

        Person noEmail = new Person();
        noEmail.setName("NoEmail");
        noEmail.setCreatedAt(ledger.now().minusMonths(1));
        noEmail = people.save(noEmail);

        Subscription sub = new Subscription();
        sub.setName("Spotify");
        sub.setTotalAmount(new BigDecimal("10.00"));
        sub.setBillingDay(1);
        sub = subscriptions.save(sub);

        Membership m1 = new Membership();
        m1.setPerson(maria);
        m1.setSubscription(sub);
        m1.setShareAmount(new BigDecimal("5.00"));
        m1.setCreatedAt(ledger.now().minusMonths(1));
        memberships.save(m1);

        Membership m2 = new Membership();
        m2.setPerson(john);
        m2.setSubscription(sub);
        m2.setShareAmount(new BigDecimal("5.00"));
        m2.setCreatedAt(ledger.now().minusMonths(1));
        memberships.save(m2);

        int sentCount = notifications.sendAllOverdueReminders(true);
        assertThat(sentCount).isEqualTo(2);
        verify(mailer, times(2)).send(any(MimeMessage.class));
    }
}
