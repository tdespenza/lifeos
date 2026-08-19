package com.lifeos.assistant.tool;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Independent bounded transaction boundaries for confirmation reservation and race recovery. */
@Service
public class AssistantToolConfirmationTransactions {

    static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final AssistantToolConfirmationRepository repository;

    public AssistantToolConfirmationTransactions(AssistantToolConfirmationRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public AssistantToolConfirmation reserve(
            UUID conversationId,
            UUID ownerAccountId,
            AssistantToolOperation operation,
            String idempotencyKeyHash,
            String requestFingerprint) {
        return repository.saveAndFlush(new AssistantToolConfirmation(
                conversationId, ownerAccountId, operation, idempotencyKeyHash, requestFingerprint));
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Optional<AssistantToolConfirmation> findExisting(
            UUID conversationId, UUID ownerAccountId, String idempotencyKeyHash) {
        return repository.findByConversationIdAndOwnerAccountIdAndIdempotencyKeyHash(
                conversationId, ownerAccountId, idempotencyKeyHash);
    }
}
