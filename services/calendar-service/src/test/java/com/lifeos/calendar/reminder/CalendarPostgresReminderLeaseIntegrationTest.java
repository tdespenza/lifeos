package com.lifeos.calendar.reminder;

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
import com.lifeos.calendar.domain.CalendarOccurrenceRepository;
import com.lifeos.calendar.domain.CalendarReminderRepository;
import com.lifeos.calendar.domain.CalendarScheduleLockRepository;
import com.lifeos.calendar.domain.CalendarSecurityAuditEventRepository;
import com.lifeos.calendar.domain.CalendarTimeBlockRepository;
import com.lifeos.calendar.service.CalendarManagementService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL-only proof that SKIP LOCKED reminder leases create one producer outbox event. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "calendar.idempotency-secret=postgres-calendar-idempotency-secret",
    "calendar.audit-client-fingerprint-secret=postgres-calendar-audit-secret",
    "calendar.recurrence.materializer-enabled=false",
    "calendar.reminders.scheduler-enabled=false",
    "calendar.outbox.relay-enabled=false",
    "identity.workload-token=postgres-calendar-workload-token"
})
class CalendarPostgresReminderLeaseIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "abababababababababababababababababababababababababababababababab";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lifeos_calendar")
            .withUsername("lifeos")
            .withPassword("test-only-postgres-password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private CalendarManagementService calendarService;

    @Autowired
    private CalendarReminderTransactions reminderTransactions;

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
    void concurrentSchedulersClaimOneDueReminderAndWriteOneOutboxEvent() throws Exception {
        Instant start = Instant.now().minusSeconds(1);
        calendarService.createEvent(
                subject,
                new CreateCalendarEventRequest(
                        "Private event",
                        "Sensitive body",
                        start,
                        start.plusSeconds(3_600),
                        "America/Chicago",
                        null,
                        List.of(new CalendarReminderRequest(0, Set.of(NotificationChannel.REALTIME)))),
                "calendar-postgres-reminder-0001");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> claimAfterStart(ready, startSignal));
            Future<Integer> second = executor.submit(() -> claimAfterStart(ready, startSignal));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            startSignal.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(outboxRepository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private int claimAfterStart(CountDownLatch ready, CountDownLatch startSignal) throws Exception {
        ready.countDown();
        if (!startSignal.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Calendar reminder scheduler did not receive its start signal");
        }
        return reminderTransactions.claimDueAndCreateOutbox();
    }
}
