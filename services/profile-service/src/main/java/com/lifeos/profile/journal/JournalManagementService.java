package com.lifeos.profile.journal;

import com.lifeos.profile.api.CreateJournalEntryRequest;
import com.lifeos.profile.api.JournalEntryResponse;
import com.lifeos.profile.api.UpdateJournalEntryRequest;
import com.lifeos.profile.authorization.ProfileSubject;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Validates the journal contract before delegating to the bounded persistence boundary. */
@Service
public class JournalManagementService {

    private final JournalStore store;

    public JournalManagementService(JournalStore store) {
        this.store = store;
    }

    public JournalMutationResponse create(ProfileSubject subject, String idempotencyKey, CreateJournalEntryRequest request) {
        JournalMutationResult result = store.create(subject.accountId(), idempotencyKey, request.title(), request.content());
        return new JournalMutationResponse(JournalEntryResponse.from(result.entry()), result.replayed());
    }

    public List<JournalEntryResponse> list(ProfileSubject subject, int limit) {
        return store.list(subject.accountId(), limit).stream().map(JournalEntryResponse::from).toList();
    }

    public JournalEntryResponse get(ProfileSubject subject, UUID entryId) {
        return JournalEntryResponse.from(store.get(subject.accountId(), entryId));
    }

    public JournalMutationResponse update(
            ProfileSubject subject,
            UUID entryId,
            long expectedVersion,
            String idempotencyKey,
            UpdateJournalEntryRequest request) {
        JournalMutationResult result = store.update(
                subject.accountId(), entryId, expectedVersion, idempotencyKey, request.title(), request.content());
        return new JournalMutationResponse(JournalEntryResponse.from(result.entry()), result.replayed());
    }

    public boolean delete(ProfileSubject subject, UUID entryId, long expectedVersion, String idempotencyKey) {
        return store.delete(subject.accountId(), entryId, expectedVersion, idempotencyKey);
    }

    public record JournalMutationResponse(JournalEntryResponse body, boolean replayed) {
    }
}
