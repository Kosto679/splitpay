package com.splitpay.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class AutomatedReminderSchedulerTest {

    @Test
    void runScheduledOverdueRemindersInvokesJobWhenConfigured() {
        NotificationService notifications = mock(NotificationService.class);
        when(notifications.isConfigured()).thenReturn(true);
        when(notifications.runAutomatedOverdueJob())
                .thenReturn(new NotificationService.AutomatedAlertResult(1, 1, true, "10.00 EUR"));

        AutomatedReminderScheduler scheduler = new AutomatedReminderScheduler(notifications);
        scheduler.runScheduledOverdueReminders();

        verify(notifications).runAutomatedOverdueJob();
    }

    @Test
    void runScheduledOverdueRemindersSkipsWhenNotConfigured() {
        NotificationService notifications = mock(NotificationService.class);
        when(notifications.isConfigured()).thenReturn(false);

        AutomatedReminderScheduler scheduler = new AutomatedReminderScheduler(notifications);
        scheduler.runScheduledOverdueReminders();

        verify(notifications, never()).runAutomatedOverdueJob();
    }
}
