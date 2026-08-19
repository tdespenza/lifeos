package com.lifeos.documentvault.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.documentvault.authorization.DocumentVaultSubject;
import com.lifeos.documentvault.service.DocumentView;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims an owner-scoped durable reservation before a mutation, commits its immutable response
 * snapshot with the resource write, and replays that snapshot exactly on a matching retry.
 */
@Service
public class DocumentCommandIdempotencyService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final DocumentCommandIdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String idempotencySecret;
    private final TransactionTemplate reservationTransaction;
    private final TransactionTemplate completionTransaction;
    private final TransactionTemplate cleanupTransaction;

    public DocumentCommandIdempotencyService(
            DocumentCommandIdempotencyRepository repository,
            ObjectMapper objectMapper,
            Clock documentVaultClock,
            com.lifeos.documentvault.config.DocumentVaultServiceProperties properties,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        clock = documentVaultClock;
        idempotencySecret = properties.getIdempotencySecret();
        reservationTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        completionTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRED);
        cleanupTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The completion callback executes only for an uncompleted matching reservation and receives
     * its stable candidate document ID. Authentication remains outside this method so retries do
     * not bypass current bearer validation.
     */
    public DocumentCommandResult execute(
            DocumentVaultSubject subject,
            DocumentCommandOperation operation,
            UUID candidateDocumentId,
            long expectedVersion,
            String idempotencyKey,
            String requestFingerprint,
            Function<UUID, DocumentView> completion) {
        String rawKey = DocumentIdempotencyKey.requireValid(idempotencyKey);
        String keyHash = DocumentCommandFingerprint.keyHash(rawKey, idempotencySecret);
        DocumentCommandIdempotency reservation = reserveOrLoad(
                subject, operation, candidateDocumentId, keyHash, requestFingerprint, expectedVersion);
        if (!reservation.matchesRequest(requestFingerprint)) {
            throw new DocumentIdempotencyConflictException();
        }
        try {
            return Objects.requireNonNull(completionTransaction.execute(status -> completeOrReplay(
                    reservation.getId(), subject, operation, requestFingerprint, completion)));
        } catch (DocumentCommandRejectedException exception) {
            discardPendingReservation(subject, operation, keyHash, requestFingerprint);
            throw exception;
        } catch (DocumentIdempotencyConflictException | DocumentIdempotencyUnavailableException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new DocumentIdempotencyUnavailableException();
        }
    }

    private DocumentCommandResult completeOrReplay(
            UUID reservationId,
            DocumentVaultSubject subject,
            DocumentCommandOperation operation,
            String requestFingerprint,
            Function<UUID, DocumentView> completion) {
        DocumentCommandIdempotency reservation = repository
                .findByIdAndScopeForUpdate(reservationId, subject.accountId(), subject.tenantId(), operation)
                .orElseThrow(DocumentIdempotencyUnavailableException::new);
        if (!reservation.matchesRequest(requestFingerprint)) {
            throw new DocumentIdempotencyConflictException();
        }
        if (reservation.isCompleted()) {
            return new DocumentCommandResult(deserialize(reservation.completedSnapshot()), true);
        }
        DocumentView result = Objects.requireNonNull(
                completion.apply(reservation.getDocumentId()), "completion result must not be null");
        reservation.complete(serialize(result), clock.instant());
        return new DocumentCommandResult(result, false);
    }

    private DocumentCommandIdempotency reserveOrLoad(
            DocumentVaultSubject subject,
            DocumentCommandOperation operation,
            UUID candidateDocumentId,
            String keyHash,
            String requestFingerprint,
            long expectedVersion) {
        Optional<DocumentCommandIdempotency> existing = findExisting(subject, operation, keyHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return Objects.requireNonNull(reservationTransaction.execute(status -> repository.saveAndFlush(
                    new DocumentCommandIdempotency(
                            subject.accountId(),
                            subject.tenantId(),
                            operation,
                            candidateDocumentId,
                            keyHash,
                            requestFingerprint,
                            expectedVersion,
                            clock.instant()))));
        } catch (DataIntegrityViolationException exception) {
            return findExisting(subject, operation, keyHash).orElseThrow(DocumentIdempotencyUnavailableException::new);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new DocumentIdempotencyUnavailableException();
        }
    }

    private void discardPendingReservation(
            DocumentVaultSubject subject,
            DocumentCommandOperation operation,
            String keyHash,
            String requestFingerprint) {
        try {
            cleanupTransaction.executeWithoutResult(status -> repository
                    .findByScopeAndKeyForUpdate(subject.accountId(), subject.tenantId(), operation, keyHash)
                    .filter(reservation -> !reservation.isCompleted())
                    .filter(reservation -> reservation.matchesRequest(requestFingerprint))
                    .ifPresent(reservation -> {
                        repository.delete(reservation);
                        repository.flush();
                    }));
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new DocumentIdempotencyUnavailableException();
        }
    }

    private Optional<DocumentCommandIdempotency> findExisting(
            DocumentVaultSubject subject, DocumentCommandOperation operation, String keyHash) {
        try {
            return repository.findByActorAccountIdAndTenantIdAndOperationAndIdempotencyKeyHash(
                    subject.accountId(), subject.tenantId(), operation, keyHash);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new DocumentIdempotencyUnavailableException();
        }
    }

    private String serialize(DocumentView result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new DocumentIdempotencyUnavailableException();
        }
    }

    private DocumentView deserialize(String snapshot) {
        try {
            return objectMapper.readValue(snapshot, DocumentView.class);
        } catch (JsonProcessingException exception) {
            throw new DocumentIdempotencyUnavailableException();
        }
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, int propagation) {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(propagation);
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return transaction;
    }
}
