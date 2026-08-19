package com.lifeos.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.lifeos.calendar.api.CalendarReminderRequest;
import com.lifeos.calendar.api.CreateCalendarEventRequest;
import com.lifeos.calendar.api.CreateCalendarTimeBlockRequest;
import com.lifeos.calendar.api.UpdateCalendarEventRequest;
import com.lifeos.calendar.authorization.CalendarAccessService;
import com.lifeos.calendar.authorization.CalendarAuthorizationActions;
import com.lifeos.calendar.authorization.CalendarSubject;
import com.lifeos.calendar.domain.CalendarEventReminderRepository;
import com.lifeos.calendar.domain.CalendarEventRepository;
import com.lifeos.calendar.domain.CalendarLifecycleTransitionException;
import com.lifeos.calendar.domain.CalendarMutationIdempotencyRepository;
import com.lifeos.calendar.domain.CalendarOutboxDeadLetterRepository;
import com.lifeos.calendar.domain.CalendarOutboxEventRepository;
import com.lifeos.calendar.domain.CalendarOccurrenceRepository;
import com.lifeos.calendar.domain.CalendarReminderRepository;
import com.lifeos.calendar.domain.CalendarScheduleLockRepository;
import com.lifeos.calendar.domain.CalendarSecurityAuditEventRepository;
import com.lifeos.calendar.domain.CalendarTimeBlockRepository;
import com.lifeos.calendar.reminder.CalendarReminderTransactions;
import com.lifeos.events.v1.NotificationChannel;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** H2 service transaction coverage for idempotency, conflicts, reminder privacy, and audit rows. */
@SpringBootTest
@ActiveProfiles("test")
class CalendarServiceIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    @Autowired
    private CalendarManagementService service;

    @Autowired
    private CalendarReminderTransactions reminderTransactions;

    @Autowired
    private CalendarEventRepository eventRepository;

    @Autowired
    private CalendarEventReminderRepository eventReminderRepository;

    @Autowired
    private CalendarOccurrenceRepository occurrenceRepository;

    @Autowired
    private CalendarReminderRepository reminderRepository;

    @Autowired
    private CalendarOutboxEventRepository outboxRepository;

    @Autowired
    private CalendarOutboxDeadLetterRepository deadLetterRepository;

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
    void setUp() {
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
    void replaysTheExactCreateSnapshotForTheSameIdempotencyKey() {
        CreateCalendarEventRequest request = eventRequest("Private appointment", Instant.now().plusSeconds(120), List.of());

        var first = service.createEvent(subject, request, "calendar-create-retry-0001");
        var replay = service.createEvent(subject, request, "calendar-create-retry-0001");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(1);
        assertThat(auditRepository.count()).isEqualTo(1);
    }

    @Test
    void rechecksLocalOwnerScopeEvenIfAnUpstreamDecisionIsMistakenlyPermissive() {
        var created = service.createEvent(
                subject,
                eventRequest("Private event", Instant.now().plusSeconds(120), List.of()),
                "calendar-local-owner-scope");
        CalendarSubject otherSubject = new CalendarSubject(
                UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);

        assertThatThrownBy(() -> service.getEvent(otherSubject, created.body().id()))
                .isInstanceOf(CalendarResourceNotFoundException.class);
    }

    @Test
    void rejectsAnInvalidCancelledEventTransitionWithoutChangingTheEventAgain() {
        Instant start = Instant.now().plusSeconds(120);
        var created = service.createEvent(subject, eventRequest("Private event", start, List.of()), "calendar-cancel-event");
        var cancelled = service.cancelEvent(
                subject, created.body().id(), created.body().version(), "calendar-cancel-event-transition");

        assertThatThrownBy(() -> service.updateEvent(
                        subject,
                        created.body().id(),
                        cancelled.body().version(),
                        new UpdateCalendarEventRequest(
                                "Updated private event", null, start, start.plusSeconds(3_600), "America/Chicago", null, List.of()),
                        "calendar-update-cancelled-event"))
                .isInstanceOf(CalendarLifecycleTransitionException.class);
        assertThat(eventRepository.findById(created.body().id()).orElseThrow().getStatus())
                .isEqualTo(com.lifeos.calendar.domain.CalendarEventStatus.CANCELLED);
    }

    @Test
    void rejectsAnOverlappingFocusBlockWithoutMovingTheEvent() {
        Instant start = Instant.now().plusSeconds(120);
        service.createEvent(subject, eventRequest("Meeting", start, List.of()), "calendar-event-before-block");

        assertThatThrownBy(() -> service.createTimeBlock(
                        subject,
                        new CreateCalendarTimeBlockRequest(
                                com.lifeos.calendar.domain.CalendarLinkType.FOCUS,
                                null,
                                start.plusSeconds(60),
                                start.plusSeconds(1_800),
                                "America/Chicago"),
                        "calendar-overlap-block-key"))
                .isInstanceOf(CalendarConflictException.class);
        assertThat(timeBlockRepository.count()).isZero();
    }

    @Test
    void authorizesBeforeRejectingAnUnsupportedTaskGoalLink() {
        assertThatThrownBy(() -> service.createTimeBlock(
                        subject,
                        new CreateCalendarTimeBlockRequest(
                                com.lifeos.calendar.domain.CalendarLinkType.GOAL,
                                UUID.randomUUID(),
                                Instant.now().plusSeconds(120),
                                Instant.now().plusSeconds(1_920),
                                "America/Chicago"),
                        "calendar-authorize-before-unsupported-link"))
                .isInstanceOf(UnsupportedCalendarLinkException.class);

        verify(accessService).authorize(eq(subject), eq(CalendarAuthorizationActions.TIME_BLOCK_CREATE), any());
    }

    @Test
    void acceptsAnAdjacentFocusBlockBecauseCalendarIntervalsAreHalfOpen() {
        Instant start = Instant.now().plusSeconds(120);
        service.createEvent(subject, eventRequest("Meeting", start, List.of()), "calendar-adjacent-event");

        var result = service.createTimeBlock(
                subject,
                new CreateCalendarTimeBlockRequest(
                        com.lifeos.calendar.domain.CalendarLinkType.FOCUS,
                        null,
                        start.plusSeconds(3_600),
                        start.plusSeconds(5_400),
                        "America/Chicago"),
                "calendar-adjacent-block");

        assertThat(result.replayed()).isFalse();
        assertThat(timeBlockRepository.count()).isEqualTo(1L);
    }

    @Test
    void releasesARejectedMutationReservationSoTheSameKeyCanRetryAValidCommand() {
        Instant start = Instant.now().plusSeconds(120);
        service.createEvent(subject, eventRequest("Meeting", start, List.of()), "calendar-retry-event");
        String idempotencyKey = "calendar-conflict-retry-key";

        assertThatThrownBy(() -> service.createTimeBlock(
                        subject,
                        new CreateCalendarTimeBlockRequest(
                                com.lifeos.calendar.domain.CalendarLinkType.FOCUS,
                                null,
                                start.plusSeconds(60),
                                start.plusSeconds(1_800),
                                "America/Chicago"),
                        idempotencyKey))
                .isInstanceOf(CalendarConflictException.class);

        var result = service.createTimeBlock(
                subject,
                new CreateCalendarTimeBlockRequest(
                        com.lifeos.calendar.domain.CalendarLinkType.FOCUS,
                        null,
                        start.plusSeconds(3_600),
                        start.plusSeconds(5_400),
                        "America/Chicago"),
                idempotencyKey);

        assertThat(result.replayed()).isFalse();
        assertThat(timeBlockRepository.count()).isEqualTo(1L);
    }

    @Test
    void concurrentOverlappingFirstTimeBlocksSerializeThroughTheDurableOwnerGuard() throws Exception {
        Instant start = Instant.now().plusSeconds(300);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        try {
            Future<TimeBlockAttempt> first = executor.submit(() -> createOverlappingBlock(ready, startSignal, start, "a"));
            Future<TimeBlockAttempt> second = executor.submit(() -> createOverlappingBlock(ready, startSignal, start, "b"));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            startSignal.countDown();
            List<TimeBlockAttempt> attempts = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));

            assertThat(attempts.stream().filter(TimeBlockAttempt::succeeded).count()).isEqualTo(1L);
            assertThat(attempts.stream().map(TimeBlockAttempt::failure).filter(java.util.Objects::nonNull).toList())
                    .hasSize(1)
                    .allMatch(CalendarConflictException.class::isInstance);
            assertThat(timeBlockRepository.count()).isEqualTo(1L);
            assertThat(scheduleLockRepository.count()).isEqualTo(1L);
            assertThat(idempotencyRepository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void writesOnlyGenericV2ContentToTheReminderOutbox() {
        String sensitiveTitle = "Oncology appointment at 123 Private Street";
        String sensitiveDescription = "Diagnosis and finance details";
        Instant start = Instant.now().minusSeconds(1);
        CreateCalendarEventRequest request = new CreateCalendarEventRequest(
                sensitiveTitle,
                sensitiveDescription,
                start,
                start.plusSeconds(3_600),
                "America/Chicago",
                null,
                List.of(new CalendarReminderRequest(0, Set.of(NotificationChannel.REALTIME))));
        service.createEvent(subject, request, "calendar-private-reminder-1");

        assertThat(reminderTransactions.claimDueAndCreateOutbox()).isEqualTo(1);
        String payload = outboxRepository.findAll().getFirst().getPayloadJson();
        assertThat(payload)
                .contains("calendar.reminder")
                .contains("Calendar reminder")
                .contains("America/Chicago")
                .doesNotContain(sensitiveTitle)
                .doesNotContain(sensitiveDescription);
    }

    private CreateCalendarEventRequest eventRequest(
            String title, Instant start, List<CalendarReminderRequest> reminders) {
        return new CreateCalendarEventRequest(
                title,
                null,
                start,
                start.plusSeconds(3_600),
                "America/Chicago",
                null,
                reminders);
    }

    private TimeBlockAttempt createOverlappingBlock(
            CountDownLatch ready, CountDownLatch startSignal, Instant start, String suffix) throws Exception {
        ready.countDown();
        if (!startSignal.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent Calendar time-block write did not receive its start signal");
        }
        try {
            return TimeBlockAttempt.success(service.createTimeBlock(
                    subject,
                    new CreateCalendarTimeBlockRequest(
                            com.lifeos.calendar.domain.CalendarLinkType.FOCUS,
                            null,
                            start,
                            start.plusSeconds(1_800),
                            "America/Chicago"),
                    "calendar-concurrent-block-" + suffix));
        } catch (RuntimeException exception) {
            return TimeBlockAttempt.failure(exception);
        }
    }

    private record TimeBlockAttempt(Object result, Throwable failure) {

        private static TimeBlockAttempt success(Object result) {
            return new TimeBlockAttempt(result, null);
        }

        private static TimeBlockAttempt failure(Throwable failure) {
            return new TimeBlockAttempt(null, failure);
        }

        private boolean succeeded() {
            return result != null;
        }
    }
}
