package com.lifeos.calendar.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Executable public contract for ETag/idempotency and safe unsupported Task/Goal links. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarControllerContractTest {

    private static final String ACCESS_TOKEN_PROOF =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

    @Autowired
    private MockMvc mockMvc;

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
        CalendarSubject subject = new CalendarSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        when(accessService.authenticate("Bearer calendar-contract-token")).thenReturn(subject);
        // The interaction is verified by the service/integration suite; this boundary test models an Identity allow.
        org.mockito.Mockito.doNothing().when(accessService).authorize(any(), any(), any());
    }

    @Test
    void createsAndReplaysAnEventWithETagAndLocation() throws Exception {
        String body = """
                {"title":"Planning session","description":null,"startAt":"2026-08-17T16:00:00Z","endAt":"2026-08-17T17:00:00Z","timeZone":"America/Chicago","recurrence":null,"reminders":[]}
                """;

        mockMvc.perform(post("/api/v1/calendar/events")
                        .header("Authorization", "Bearer calendar-contract-token")
                        .header("Idempotency-Key", "calendar-controller-retry-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/v1/calendar/events/")))
                .andExpect(jsonPath("$.title").value("Planning session"));

        mockMvc.perform(post("/api/v1/calendar/events")
                        .header("Authorization", "Bearer calendar-contract-token")
                        .header("Idempotency-Key", "calendar-controller-retry-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.title").value("Planning session"));
    }

    @Test
    void rejectsTaskLinksUntilTheTaskGoalAuthorizationProjectionExists() throws Exception {
        String body = """
                {"linkType":"GOAL","linkedResourceId":"%s","startAt":"2026-08-17T18:00:00Z","endAt":"2026-08-17T19:00:00Z","timeZone":"America/Chicago"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/calendar/time-blocks")
                        .header("Authorization", "Bearer calendar-contract-token")
                        .header("Idempotency-Key", "calendar-goal-link-rejected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Task and Goal")));
    }
}
