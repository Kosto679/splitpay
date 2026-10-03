package com.splitpay.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "splitpay.auto-reminders.enabled", havingValue = "true", matchIfMissing = true)
public class AutomatedReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomatedReminderScheduler.class);

    private final NotificationService notifications;

    public AutomatedReminderScheduler(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Scheduled(
            cron = "${splitpay.auto-reminders.cron:0 0 10 * * *}",
            zone = "${splitpay.timezone:Europe/Athens}"
    )
    public void runScheduledOverdueReminders() {
        if (!notifications.isConfigured()) {
            log.debug("Email is not configured; automated reminder scheduler skipping run.");
            return;
        }
        log.info("Starting automated overdue payment reminder job...");
        try {
            NotificationService.AutomatedAlertResult result = notifications.runAutomatedOverdueJob();
            log.info("Automated overdue payment reminder job completed: {} member reminder(s) sent, admin alert: {}",
                    result.remindersSent(), result.adminNotified() ? "sent" : "skipped");
        } catch (Exception e) {
            log.error("Error executing automated overdue payment reminder job: {}", e.getMessage(), e);
        }
    }
}
