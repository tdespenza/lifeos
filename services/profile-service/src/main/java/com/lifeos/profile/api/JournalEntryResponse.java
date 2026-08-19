package com.lifeos.profile.api;

import com.lifeos.profile.journal.JournalEntry;
import java.time.Instant;
import java.util.UUID;

public record JournalEntryResponse(
        UUID id, String title, String content, Instant createdAt, Instant updatedAt, long version) {

    public static JournalEntryResponse from(JournalEntry entry) {
        return new JournalEntryResponse(
                entry.id(), entry.title(), entry.content(), entry.createdAt(), entry.updatedAt(), entry.version());
    }
}
