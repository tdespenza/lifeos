package com.lifeos.assistant.journal;

import com.lifeos.assistant.authorization.AssistantSubject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Workload-authenticated boundary for consented, bounded Profile journal context. */
public interface AssistantJournalClient {

    JournalSnapshot journals(AssistantSubject subject, int maxEntries, int maxCharacters);

    PersonalizationSnapshot personalization(AssistantSubject subject);

    record JournalSnapshot(List<JournalEntry> entries, boolean truncated, List<String> limitations) {
    }

    record JournalEntry(UUID id, String title, String content, Instant createdAt, Instant updatedAt, boolean truncated) {
    }

    record PersonalizationSnapshot(boolean consentGranted, boolean personalizationEnabled, List<String> allowedContextCategories) {
    }
}
