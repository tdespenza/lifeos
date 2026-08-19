package com.lifeos.assistant.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantJournalSummaryServiceTest {

    private static final AssistantSubject SUBJECT = new AssistantSubject(
            UUID.randomUUID(), UUID.randomUUID(), "password", "a".repeat(64));

    @Mock
    private AssistantJournalClient journalClient;

    @Mock
    private AssistantAuditService auditService;

    @Test
    void createsBoundedDeterministicDigestAndAuditsSourceIds() {
        UUID journalId = UUID.randomUUID();
        when(journalClient.journals(SUBJECT, 5, 4_000))
                .thenReturn(new AssistantJournalClient.JournalSnapshot(
                        List.of(new AssistantJournalClient.JournalEntry(
                                journalId,
                                "Renewal",
                                "Renewal date is Friday. More private detail.",
                                Instant.parse("2026-08-18T10:00:00Z"),
                                Instant.parse("2026-08-18T10:00:00Z"),
                                false)),
                        false,
                        List.of()));

        AssistantJournalSummaryService.JournalSummary result = new AssistantJournalSummaryService(
                journalClient, auditService).summarize(SUBJECT, null, null);

        assertThat(result.content()).isEqualTo("Renewal: Renewal date is Friday.");
        assertThat(result.sourceJournalIds()).containsExactly(journalId);
        verify(auditService).record(any());
    }
}
