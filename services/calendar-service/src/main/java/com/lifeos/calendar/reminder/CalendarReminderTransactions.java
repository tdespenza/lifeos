package com.lifeos.calendar.reminder;

import com.lifeos.calendar.config.CalendarProperties;
import com.lifeos.calendar.domain.CalendarEvent;
import com.lifeos.calendar.domain.CalendarEventRepository;
import com.lifeos.calendar.domain.CalendarEventStatus;
import com.lifeos.calendar.domain.CalendarOutboxEventRepository;
import com.lifeos.calendar.domain.CalendarReminder;
import com.lifeos.calendar.domain.CalendarReminderRepository;
import com.lifeos.calendar.outbox.CalendarReminderNotificationOutboxFactory;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically turns a due reminder lease into a Calendar producer outbox record. */
@Service
public class CalendarReminderTransactions {

    private final CalendarReminderRepository reminderRepository;
    private final CalendarEventRepository eventRepository;
    private final CalendarOutboxEventRepository outboxRepository;
    private final CalendarReminderNotificationOutboxFactory outboxFactory;
    private final CalendarProperties properties;
    private final Clock clock;

    public CalendarReminderTransactions(
            CalendarReminderRepository reminderRepository,
            CalendarEventRepository eventRepository,
            CalendarOutboxEventRepository outboxRepository,
            CalendarReminderNotificationOutboxFactory outboxFactory,
            CalendarProperties properties,
            Clock clock) {
        this.reminderRepository = reminderRepository;
        this.eventRepository = eventRepository;
        this.outboxRepository = outboxRepository;
        this.outboxFactory = outboxFactory;
        this.properties = properties;
        this.clock = clock;
    }

    /** Claims at most the configured batch under SKIP LOCKED and commits event/outbox state together. */
    @Transactional
    public int claimDueAndCreateOutbox() {
        Instant now = clock.instant();
        int created = 0;
        for (CalendarReminder reminder : reminderRepository.findClaimableForUpdate(
                now, properties.getReminders().getBatchSize())) {
            CalendarEvent event = eventRepository.findById(reminder.getEventId()).orElse(null);
            if (event == null || event.getStatus() != CalendarEventStatus.ACTIVE) {
                reminder.cancel(now);
                continue;
            }
            if (!reminder.getDueAt().plusSeconds(60).isAfter(now)) {
                // A bounded scheduler never produces arbitrarily late historical notifications.
                reminder.expire(now);
                continue;
            }
            var lease = reminder.claim(now, properties.getReminders().getLeaseDuration());
            outboxRepository.save(outboxFactory.create(reminder, event.getVersion()));
            reminder.markOutboxed(lease, now);
            created++;
        }
        return created;
    }
}
