package com.lifeos.calendar.reminder;

import com.lifeos.calendar.outbox.CalendarMessagingMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded scheduler that makes due reminders durable producer work without blocking HTTP writes. */
@Component
@ConditionalOnProperty(value = "calendar.reminders.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class CalendarReminderScheduler {

    private final CalendarReminderTransactions transactions;
    private final CalendarMessagingMetrics metrics;

    public CalendarReminderScheduler(CalendarReminderTransactions transactions, CalendarMessagingMetrics metrics) {
        this.transactions = transactions;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${calendar.reminders.poll-delay:1s}")
    public void enqueueDueReminders() {
        metrics.recordReminderClaim(transactions.claimDueAndCreateOutbox());
    }
}
