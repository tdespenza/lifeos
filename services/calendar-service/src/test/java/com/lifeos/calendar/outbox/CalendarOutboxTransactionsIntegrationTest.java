package com.lifeos.calendar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.calendar.api.CalendarReminderRequest;
import com.lifeos.calendar.api.CreateCalendarEventRequest;
import com.lifeos.calendar.authorization.CalendarAccessService;
import com.lifeos.calendar.authorization.CalendarSubject;
import com.lifeos.calendar.domain.CalendarEventReminderRepository;
import com.lifeos.calendar.domain.CalendarEventRepository;
import com.lifeos.calendar.domain.CalendarMutationIdempotencyRepository;
import com.lifeos.calendar.domain.CalendarOutboxDeadLetterRepository;
import com.lifeos.calendar.domain.CalendarOutboxEventRepository;
import com.lifeos.calendar.domain.CalendarOutboxState;
import com.lifeos.calendar.domain.CalendarOccurrenceRepository;
import com.lifeos.calendar.domain.CalendarReminderRepository;
import com.lifeos.calendar.domain.CalendarScheduleLockRepository;
import com.lifeos.calendar.domain.CalendarSecurityAuditEventRepository;
import com.lifeos.calendar.domain.CalendarTimeBlockRepository;
import com.lifeos.calendar.reminder.CalendarReminderTransactions;
import com.lifeos.calendar.service.CalendarManagementService;
import com.lifeos.events.v1.NotificationChannel;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** H2 transaction coverage for bounded producer retry exhaustion and local dead-letter durability. */
@SpringBootTest(properties = "calendar.outbox.max-attempts=1")
@ActiveProfiles("test")
class CalendarOutboxTransactionsIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    @Autowired
    private CalendarManagementService calendarService;

    @Autowired
    private CalendarReminderTransactions reminderTransactions;

    @Autowired
    private CalendarOutboxTransactions outboxTransactions;

    @Autowired
    private CalendarOutboxEventRepository outboxRepository;

    @Autowired
    private CalendarOutboxDeadLetterRepository deadLetterRepository;

    @Autowired
    private CalendarReminderRepository reminderRepository;

    @Autowired
    private CalendarOccurrenceRepository occurrenceRepository;

    @Autowired
    private CalendarEventReminderRepository eventReminderRepository;

    @Autowired
    private CalendarEventRepository eventRepository;

    @Autowired
    private CalendarTimeBlockRepository timeBlockRepository;

    @Autowired
    private CalendarScheduleLockRepository scheduleLockRepository;

    @Autowired
    private CalendarMutationIdempotencyRepository idempotencyRepository;

    @Autowired
    private CalendarSecurityAuditEventRepository auditRepository;

    @MockitoBean
    private CalendarAccessService accessService;

    private CalendarSubject subject;

    @BeforeEach
    void cleanDatabase() {
        deadLetterRepository.deleteAll();
        outboxRepository.deleteAll();
        reminderRepository.deleteAll();
        occurrenceRepository.deleteAll();
        eventReminderRepository.deleteAll();
        eventRepository.deleteAll();
        timeBlockRepository.deleteAll();
        scheduleLockRepository.deleteAll();
        idempotencyRepository.deleteAll();
        auditRepository.deleteAll();
        subject = new CalendarSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void deadLettersOnlyAfterTheConfiguredBoundedProducerAttempt() {
        Instant start = Instant.now().minusSeconds(1);
        calendarService.createEvent(
                subject,
                new CreateCalendarEventRequest(
                        "Private event",
                        "sensitive details stay out of the notification payload",
                        start,
                        start.plusSeconds(3_600),
                        "America/Chicago",
                        null,
                        List.of(new CalendarReminderRequest(0, Set.of(NotificationChannel.REALTIME)))),
                "calendar-outbox-dead-letter-0001");
        assertThat(reminderTransactions.claimDueAndCreateOutbox()).isEqualTo(1);

        ClaimedCalendarOutboxEvent claimed = outboxTransactions.claimBatch().getFirst();
        boolean retryScheduled = outboxTransactions.rescheduleOrDeadLetter(claimed);

        assertThat(retryScheduled).isFalse();
        assertThat(deadLetterRepository.count()).isEqualTo(1L);
        assertThat(outboxRepository.findById(claimed.id()).orElseThrow().getState())
                .isEqualTo(CalendarOutboxState.DEAD_LETTER);
    }
}
