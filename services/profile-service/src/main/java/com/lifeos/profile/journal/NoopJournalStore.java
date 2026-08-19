package com.lifeos.profile.journal;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fail-closed default; profile never silently stores journal content in PostgreSQL. */
@Component
@ConditionalOnProperty(name = "profile.journal.enabled", havingValue = "false", matchIfMissing = true)
public class NoopJournalStore implements JournalStore {

    @Override
    public JournalMutationResult create(UUID ownerAccountId, String idempotencyKey, String title, String content) {
        throw new JournalUnavailableException();
    }

    @Override
    public List<JournalEntry> list(UUID ownerAccountId, int requestedLimit) {
        throw new JournalUnavailableException();
    }

    @Override
    public JournalEntry get(UUID ownerAccountId, UUID entryId) {
        throw new JournalUnavailableException();
    }

    @Override
    public JournalMutationResult update(
            UUID ownerAccountId, UUID entryId, long expectedVersion, String idempotencyKey, String title, String content) {
        throw new JournalUnavailableException();
    }

    @Override
    public boolean delete(UUID ownerAccountId, UUID entryId, long expectedVersion, String idempotencyKey) {
        throw new JournalUnavailableException();
    }
}
