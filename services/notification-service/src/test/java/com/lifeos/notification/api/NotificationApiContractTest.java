package com.lifeos.notification.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.notification.access.NotificationAccessExceptionHandler;
import com.lifeos.notification.access.NotificationAccessService;
import com.lifeos.notification.access.NotificationSubject;
import com.lifeos.notification.endpoint.NotificationEndpointService;
import com.lifeos.notification.read.NotificationReadService;
import com.lifeos.notification.read.NotificationView;
import com.lifeos.notification.stream.NotificationStreamHub;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Executable public API contract for authenticated recipient-scoped notification history. */
@WebMvcTest(controllers = {NotificationController.class, NotificationEndpointController.class})
@Import(NotificationAccessExceptionHandler.class)
class NotificationApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationAccessService accessService;

    @MockBean
    private NotificationReadService readService;

    @MockBean
    private NotificationStreamHub streamHub;

    @MockBean
    private NotificationEndpointService endpointService;

    @Test
    void returnsOnlyTheAuthenticatedSubjectCursorPage() throws Exception {
        NotificationSubject subject = new NotificationSubject(UUID.randomUUID(), UUID.randomUUID(), "password");
        NotificationView notification = new NotificationView(
                UUID.randomUUID(),
                7,
                "calendar.reminder",
                NotificationPriority.NORMAL,
                "Reminder",
                "Event starts soon",
                null,
                Instant.parse("2026-08-17T12:00:00Z"),
                null);
        when(accessService.authenticate("Bearer access-token")).thenReturn(subject);
        when(readService.list(subject, 5, 10)).thenReturn(List.of(notification));

        mockMvc.perform(get("/api/v1/notifications?after=5&limit=10")
                        .header("Authorization", "Bearer access-token")
                        .header("X-Correlation-ID", "19dfcc38-676c-40f0-bb4c-1a085cfb5ba6"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", "19dfcc38-676c-40f0-bb4c-1a085cfb5ba6"))
                .andExpect(jsonPath("$.nextCursor").value(7))
                .andExpect(jsonPath("$.items[0].sequence").value(7))
                .andExpect(jsonPath("$.items[0].title").value("Reminder"));
    }
}
