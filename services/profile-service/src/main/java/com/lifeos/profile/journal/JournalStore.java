package com.lifeos.profile.journal;

import java.util.List;
import java.util.UUID;

/** Owner-scoped persistence boundary for journals and free-form notes. */
public interface JournalStore {

    JournalMutationResult create(UUID ownerAccountId, String idempotencyKey, String title, String content);

    List<JournalEntry> list(UUID ownerAccountId, int requestedLimit);

    JournalEntry get(UUID ownerAccountId, UUID entryId);

    JournalMutationResult update(
            UUID ownerAccountId, UUID entryId, long expectedVersion, String idempotencyKey, String title, String content);

    boolean delete(UUID ownerAccountId, UUID entryId, long expectedVersion, String idempotencyKey);
}
