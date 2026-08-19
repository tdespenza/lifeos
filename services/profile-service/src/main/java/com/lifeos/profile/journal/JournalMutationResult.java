package com.lifeos.profile.journal;

/** Immutable response plus replay marker for journal mutations. */
public record JournalMutationResult(JournalEntry entry, boolean replayed) {
}
