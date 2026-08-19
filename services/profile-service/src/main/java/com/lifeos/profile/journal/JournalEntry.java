package com.lifeos.profile.journal;

import java.time.Instant;
import java.util.UUID;

/** Decrypted owner-scoped journal representation returned only to its authenticated owner. */
public record JournalEntry(
        UUID id,
        UUID ownerAccountId,
        String title,
        String content,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
