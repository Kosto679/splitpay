package com.splitpay.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.splitpay.SplitpayProperties;
import com.splitpay.domain.Membership;
import com.splitpay.domain.Person;
import com.splitpay.repo.MembershipRepository;
import com.splitpay.repo.PersonRepository;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.transaction.Transactional;

@Service
@Transactional
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String WELCOME_TEMPLATE_PATH = "templates/email/welcome.html";
    private static final String REMINDER_TEMPLATE_PATH = "templates/email/payment-reminder.html";
    private static final String PASSWORD_RESET_TEMPLATE_PATH = "templates/email/password-reset.html";
    private static final String CHECK_IN_PASSWORD_TEMPLATE_PATH = "templates/email/check-in-password.html";

    private final Optional<JavaMailSender> mailer;
    private final SplitpayProperties props;
    private final LedgerService ledger;
    private final PersonRepository people;
    private final MembershipRepository memberships;
    private final Clock clock;

    public NotificationService(
            ObjectProvider<JavaMailSender> mailerProvider,
            SplitpayProperties props,
            LedgerService ledger,
            PersonRepository people,
            MembershipRepository memberships,
            Clock clock) {
        this.mailer = Optional.ofNullable(mailerProvider.getIfAvailable());
        this.props = props;
        this.ledger = ledger;
        this.people = people;
        this.memberships = memberships;
        this.clock = clock;
    }

    public boolean isConfigured() {
        if (mailer.isEmpty() || props.mailFrom() == null || props.mailFrom().isBlank()) {
            return false;
        }
        JavaMailSender sender = mailer.get();
        if (sender instanceof org.springframework.mail.javamail.JavaMailSenderImpl impl) {
            String host = impl.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
        }
        return true;
    }

    public boolean sendWelcomeEmail(Person person, String rawPin) {
        if (!isConfigured()) {
            log.info("Email is not configured; skipping welcome email for {}", person.getName());
            return false;
        }
        if (person.getEmail() == null || person.getEmail().isBlank()) {
            return false;
        }

        String template = loadTemplate(WELCOME_TEMPLATE_PATH);

        String passwordNotice;
        if (rawPin != null && !rawPin.isBlank()) {
            passwordNotice = "<p>Your check-in password is: <strong style=\"font-family: monospace; font-size: 16px; background: #eee; padding: 2px 6px; border-radius: 4px;\">"
                    + escapeHtml(rawPin) + "</strong></p>";
        } else if (person.hasPin()) {
            passwordNotice = "<p class=\"muted\">You already have a check-in password set. If you forgot it, ask your household admin.</p>";
        } else {
            passwordNotice = "<p class=\"muted\">Ask your household admin for your check-in password to view your status.</p>";
        }

        String supportContact = formatSupportContact();

        String html = template
                .replace("{{name}}", escapeHtml(person.getName()))
                .replace("{{checkInUrl}}", checkInUrl())
                .replace("{{passwordNotice}}", passwordNotice)
                .replace("{{supportContact}}", supportContact);

        return sendHtml(person.getEmail(), "Welcome to Splitpay!", html);
    }

    public boolean sendPasswordResetEmail(Person person, String newPassword) {
        if (!isConfigured()) {
            log.info("Email is not configured; skipping password reset email for {}", person.getName());
            return false;
        }
        if (person.getEmail() == null || person.getEmail().isBlank()) {
            return false;
        }

        String template = loadTemplate(PASSWORD_RESET_TEMPLATE_PATH);
        String html = template
                .replace("{{name}}", escapeHtml(person.getName()))
                .replace("{{password}}", escapeHtml(newPassword))
                .replace("{{checkInUrl}}", checkInUrl())
                .replace("{{supportContact}}", formatSupportContact());

        return sendHtml(person.getEmail(), "Splitpay: Your check-in password has been reset", html);
    }

    public boolean sendCheckInPasswordEmail(Person person, String password) {
        if (!isConfigured()) {
            log.info("Email is not configured; skipping check-in password email for {}", person.getName());
            return false;
        }
        if (person.getEmail() == null || person.getEmail().isBlank()) {
            return false;
        }

        String template = loadTemplate(CHECK_IN_PASSWORD_TEMPLATE_PATH);
        String html = template
                .replace("{{name}}", escapeHtml(person.getName()))
                .replace("{{password}}", escapeHtml(password))
                .replace("{{checkInUrl}}", checkInUrl())
                .replace("{{supportContact}}", formatSupportContact());

        return sendHtml(person.getEmail(), "Splitpay: Your check-in password is ready", html);
    }

    public boolean sendPaymentReminder(Person person, boolean force) {
        if (!isConfigured()) {
            log.info("Email is not configured; skipping reminder for {}", person.getName());
            return false;
        }
        if (person.getEmail() == null || person.getEmail().isBlank()) {
            return false;
        }

        List<Membership> memberList = memberships.findByPersonId(person.getId());
        List<Membership> unpaid = new ArrayList<>();
        for (Membership m : memberList) {
            if (m.isOwner()) {
                continue;
            }
            MemberStatus status = ledger.status(m);
            if (!status.paid()) {
                unpaid.add(m);
            }
        }

        if (unpaid.isEmpty()) {
            return false;
        }

        // Sort overdue first, then by subscription name
        unpaid.sort(Comparator.comparing((Membership m) -> !ledger.status(m).overdue())
                .thenComparing(m -> m.getSubscription().getName()));

        LocalDateTime now = LocalDateTime.now(clock);
        if (!force) {
            LocalDateTime threshold = now.minusDays(3);
            boolean allRecent = unpaid.stream()
                    .allMatch(m -> m.getEmailReminderSentAt() != null && m.getEmailReminderSentAt().isAfter(threshold));
            if (allRecent) {
                log.info("Skipping reminder for {} - all unpaid subscriptions reminded within last 3 days", person.getName());
                return false;
            }
        }

        String template = loadTemplate(REMINDER_TEMPLATE_PATH);

        StringBuilder itemsTable = new StringBuilder();
        Map<String, BigDecimal> totalsByCurrency = new LinkedHashMap<>();

        for (Membership m : unpaid) {
            MemberStatus s = ledger.status(m);
            String subName = escapeHtml(m.getSubscription().getName());
            int billingDay = m.getSubscription().getBillingDay();
            String detail = "Billing day: " + billingDay + daySuffix(billingDay);
            String statusBadge;
            if (s.overdue()) {
                int months = s.monthsDue();
                String periods = months > 1 ? months + " periods" : "1 period";
                statusBadge = "<span style=\"display: inline-block; background: #fee2e2; color: #b91c1c; padding: 2px 8px; border-radius: 999px; font-size: 12px; font-weight: 600;\">Overdue (" + periods + ")</span>";
            } else {
                statusBadge = "<span style=\"display: inline-block; background: #fef3c7; color: #92400e; padding: 2px 8px; border-radius: 999px; font-size: 12px; font-weight: 600;\">Due</span>";
            }

            String currency = m.getSubscription().getCurrency();
            BigDecimal remaining = s.remainingAmount();
            totalsByCurrency.merge(currency, remaining, BigDecimal::add);

            itemsTable.append("<tr>")
                    .append("<td><div class=\"sub-title\">").append(subName).append("</div>")
                    .append("<div class=\"sub-detail\">").append(detail).append("</div></td>")
                    .append("<td>").append(statusBadge).append("</td>")
                    .append("<td class=\"amount\">").append(String.format(Locale.US, "%.2f %s", remaining, currency)).append("</td>")
                    .append("</tr>\n");
        }

        String totalDue = totalsByCurrency.entrySet().stream()
                .map(e -> String.format(Locale.US, "%.2f %s", e.getValue(), e.getKey()))
                .collect(Collectors.joining(", "));

        String plural = unpaid.size() > 1 ? "s" : "";
        String supportContact = formatSupportContact();

        String html = template
                .replace("{{name}}", escapeHtml(person.getName()))
                .replace("{{plural}}", plural)
                .replace("{{itemsTable}}", itemsTable.toString())
                .replace("{{totalDue}}", totalDue)
                .replace("{{checkInUrl}}", checkInUrl())
                .replace("{{supportContact}}", supportContact);

        String subject = "Splitpay: Payment Reminder (" + totalDue + ")";
        boolean sent = sendHtml(person.getEmail(), subject, html);
        if (sent) {
            for (Membership m : unpaid) {
                m.setEmailReminderSentAt(now);
            }
            memberships.saveAll(unpaid);
        }
        return sent;
    }

    public int sendAllOverdueReminders(boolean force) {
        if (!isConfigured()) {
            log.info("Email is not configured; skipping batch reminders");
            return 0;
        }
        List<Person> all = people.findAllByOrderByNameAsc();
        int sent = 0;
        for (Person p : all) {
            if (p.getEmail() != null && !p.getEmail().isBlank()) {
                try {
                    if (sendPaymentReminder(p, force)) {
                        sent++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to send reminder to {}: {}", p.getName(), e.getMessage());
                }
            }
        }
        return sent;
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load email template from {}: {}", path, e.getMessage());
            throw new IllegalStateException("Failed to load email template: " + path, e);
        }
    }

    private boolean sendHtml(String to, String subject, String html) {
        JavaMailSender sender = mailer.orElse(null);
        if (sender == null) {
            log.warn("Email sending is not configured (no mailer bean)");
            return false;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(props.mailFrom());
            if (!props.supportEmail().isBlank()) {
                helper.setReplyTo(props.supportEmail());
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("Sent email to {} with subject '{}'", to, subject);
            return true;
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage(), e);
            return false;
        }
    }

    private String checkInUrl() {
        String base = props.publicUrl();
        if (base == null || base.isBlank()) {
            return "/check";
        }
        return base + "/check";
    }

    private String formatSupportContact() {
        if (props.supportEmail() != null && !props.supportEmail().isBlank()) {
            return " or contact <a href=\"mailto:" + escapeHtml(props.supportEmail()) + "\">"
                    + escapeHtml(props.supportEmail()) + "</a>";
        }
        return "";
    }

    private static String daySuffix(int day) {
        return switch (day) {
            case 1, 21, 31 -> "st";
            case 2, 22 -> "nd";
            case 3, 23 -> "rd";
            default -> "th";
        };
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
