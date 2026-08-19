package com.lifeos.assistant.journal;

import com.lifeos.assistant.audit.AssistantAuditOutcome;
import com.lifeos.assistant.audit.AssistantAuditRecord;
import com.lifeos.assistant.audit.AssistantAuditRequestKind;
import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.observability.RequestContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Produces a bounded deterministic journal digest until a reviewed provider is enabled. */
@Service
public class AssistantJournalSummaryService {

    private static final int DEFAULT_ENTRIES = 5;
    private static final int MAX_ENTRIES = 10;
    private static final int DEFAULT_CHARACTERS = 4_000;
    private static final int MAX_CHARACTERS = 16_384;

    private final AssistantJournalClient journalClient;
    private final AssistantAuditService auditService;

    public AssistantJournalSummaryService(AssistantJournalClient journalClient, AssistantAuditService auditService) {
        this.journalClient = journalClient;
        this.auditService = auditService;
    }

    public JournalSummary summarize(AssistantSubject subject, Integer requestedEntries, Integer requestedCharacters) {
        int maxEntries = requestedEntries == null ? DEFAULT_ENTRIES : requestedEntries;
        int maxCharacters = requestedCharacters == null ? DEFAULT_CHARACTERS : requestedCharacters;
        if (maxEntries < 1 || maxEntries > MAX_ENTRIES || maxCharacters < 256 || maxCharacters > MAX_CHARACTERS) {
            throw new AssistantJournalUnavailableException();
        }
        long started = System.nanoTime();
        try {
            AssistantJournalClient.JournalSnapshot snapshot = journalClient.journals(subject, maxEntries, maxCharacters);
            String content = snapshot.entries().stream()
                    .map(entry -> entry.title() + ": " + firstSentence(entry.content()))
                    .filter(value -> !value.isBlank())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("No journal entries were available in the requested scope.");
            if (content.length() > maxCharacters) {
                content = content.substring(0, maxCharacters);
            }
            audit(subject, AssistantAuditOutcome.ALLOWED, "JOURNAL_SUMMARY", snapshot.entries(), started, content.length());
            return new JournalSummary(content, snapshot.entries().stream().map(AssistantJournalClient.JournalEntry::id).toList(), snapshot.truncated(), snapshot.limitations());
        } catch (AssistantJournalDeniedException exception) {
            audit(subject, AssistantAuditOutcome.TOOL_REJECTED, "JOURNAL_DENIED", List.of(), started, 0);
            throw exception;
        } catch (AssistantJournalUnavailableException exception) {
            audit(subject, AssistantAuditOutcome.PROVIDER_FAILED, "JOURNAL_UNAVAILABLE", List.of(), started, 0);
            throw exception;
        }
    }

    private static String firstSentence(String content) {
        int newline = content.indexOf('\n');
        int period = content.indexOf('.');
        int end = newline >= 0 && period >= 0 ? Math.min(newline, period + 1) : Math.max(newline, period >= 0 ? period + 1 : content.length());
        return content.substring(0, Math.min(end, 512)).trim();
    }

    private void audit(
            AssistantSubject subject,
            AssistantAuditOutcome outcome,
            String summary,
            List<AssistantJournalClient.JournalEntry> entries,
            long started,
            int outputCharacters) {
        String correlationId = RequestContext.CORRELATION_ID.isBound()
                ? RequestContext.CORRELATION_ID.get()
                : "unbound";
        String sourceIds = entries.isEmpty()
                ? "NONE"
                : entries.stream().map(AssistantJournalClient.JournalEntry::id).map(UUID::toString).limit(12).reduce((a, b) -> a + "," + b).orElse("NONE");
        auditService.record(new AssistantAuditRecord(
                null,
                subject.accountId(),
                AssistantAuditRequestKind.GENERATION_REQUEST,
                outcome,
                "assistant-journal-summary-v1",
                "journal-summary",
                0,
                0,
                0,
                sourceIds,
                "NONE",
                "deterministic-journal-digest",
                "bounded-journal-summary-v1",
                summary,
                null,
                outputCharacters,
                null,
                "NONE",
                "NOT_REQUESTED",
                Math.max(0L, (System.nanoTime() - started) / 1_000_000L),
                correlationId));
    }

    public record JournalSummary(String content, List<UUID> sourceJournalIds, boolean truncated, List<String> limitations) {
    }
}
