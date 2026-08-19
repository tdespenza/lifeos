package com.lifeos.media.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.media.domain.MediaMutationIdempotency;
import com.lifeos.media.domain.MediaMutationIdempotencyRepository;
import com.lifeos.media.domain.MediaMutationIdempotencyState;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims an independent durable reservation, then atomically commits a successful response
 * snapshot with the media mutation. Deterministic business rejections remove the pending row so
 * a corrected retry is not trapped behind an unfinished command.
 */
@Service
public class MediaMutationIdempotencyService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final MediaMutationIdempotencyRepository repository;
    private final MediaMutationFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate reservationTransaction;
    private final TransactionTemplate completionTransaction;
    private final TransactionTemplate cleanupTransaction;

    public MediaMutationIdempotencyService(
            MediaMutationIdempotencyRepository repository,
            MediaMutationFingerprint fingerprint,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.clock = clock;
        reservationTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        completionTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRED);
        cleanupTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Executes one successful command at most once for an actor, operation, resource, and key. */
    public <T> MediaIdempotencyResult<T> execute(
            UUID actorAccountId,
            String tenantId,
            MediaMutationOperation operation,
            String resourceScope,
            String idempotencyKey,
            Object request,
            Long expectedVersion,
            Class<T> responseType,
            int responseStatus,
            String responseLocation,
            Supplier<T> mutation) {
        String keyHash = fingerprint.idempotencyKeyHash(idempotencyKey);
        String requestHash = fingerprint.requestHash(request);
        MediaMutationIdempotency reservation = reserveOrLoad(
                actorAccountId, tenantId, operation, resourceScope, keyHash, requestHash, expectedVersion);
        if (!reservation.matches(requestHash, expectedVersion)) {
            throw new MediaIdempotencyConflictException();
        }
        try {
            return Objects.requireNonNull(completionTransaction.execute(status -> completeOrReplay(
                    reservation.getId(),
                    requestHash,
                    expectedVersion,
                    responseType,
                    responseStatus,
                    responseLocation,
                    mutation)));
        } catch (MediaIdempotencyConflictException | MediaIdempotencyUnavailableException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new MediaIdempotencyUnavailableException(exception);
        } catch (RuntimeException exception) {
            discardPendingReservation(actorAccountId, tenantId, operation, resourceScope, keyHash, requestHash, expectedVersion);
            throw exception;
        }
    }

    private <T> MediaIdempotencyResult<T> completeOrReplay(
            UUID reservationId,
            String requestHash,
            Long expectedVersion,
            Class<T> responseType,
            int responseStatus,
            String responseLocation,
            Supplier<T> mutation) {
        MediaMutationIdempotency reservation = repository
                .findByIdForUpdate(reservationId)
                .orElseThrow(MediaIdempotencyUnavailableException::new);
        if (!reservation.matches(requestHash, expectedVersion)) {
            throw new MediaIdempotencyConflictException();
        }
        if (reservation.getState() == MediaMutationIdempotencyState.COMPLETED) {
            return new MediaIdempotencyResult<>(
                    deserialize(reservation.getResponseJson(), responseType),
                    reservation.getResponseStatus(),
                    reservation.getResponseLocation(),
                    true);
        }
        T body = Objects.requireNonNull(mutation.get(), "mutation result must not be null");
        reservation.complete(responseStatus, responseLocation, serialize(body), clock.instant());
        return new MediaIdempotencyResult<>(body, responseStatus, responseLocation, false);
    }

    private MediaMutationIdempotency reserveOrLoad(
            UUID actorAccountId,
            String tenantId,
            MediaMutationOperation operation,
            String resourceScope,
            String keyHash,
            String requestHash,
            Long expectedVersion) {
        MediaMutationIdempotency existing = findExisting(actorAccountId, tenantId, operation, resourceScope, keyHash);
        if (existing != null) {
            return existing;
        }
        try {
            return Objects.requireNonNull(reservationTransaction.execute(status -> repository.saveAndFlush(
                    MediaMutationIdempotency.pending(
                            actorAccountId,
                            tenantId,
                            operation.name(),
                            resourceScope,
                            keyHash,
                            requestHash,
                            expectedVersion,
                            clock.instant()))));
        } catch (DataIntegrityViolationException exception) {
            MediaMutationIdempotency raced = findExisting(actorAccountId, tenantId, operation, resourceScope, keyHash);
            if (raced == null) {
                throw new MediaIdempotencyUnavailableException(exception);
            }
            return raced;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new MediaIdempotencyUnavailableException(exception);
        }
    }

    private void discardPendingReservation(
            UUID actorAccountId,
            String tenantId,
            MediaMutationOperation operation,
            String resourceScope,
            String keyHash,
            String requestHash,
            Long expectedVersion) {
        try {
            cleanupTransaction.executeWithoutResult(status -> repository
                    .findByScopeForUpdate(actorAccountId, tenantId, operation.name(), resourceScope, keyHash)
                    .filter(value -> value.getState() == MediaMutationIdempotencyState.PENDING)
                    .filter(value -> value.matches(requestHash, expectedVersion))
                    .ifPresent(repository::delete));
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new MediaIdempotencyUnavailableException(exception);
        }
    }

    private MediaMutationIdempotency findExisting(
            UUID actorAccountId,
            String tenantId,
            MediaMutationOperation operation,
            String resourceScope,
            String keyHash) {
        try {
            return repository
                    .findByActorAccountIdAndTenantIdAndOperationAndResourceScopeAndIdempotencyKeyHash(
                            actorAccountId, tenantId, operation.name(), resourceScope, keyHash)
                    .orElse(null);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new MediaIdempotencyUnavailableException(exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new MediaIdempotencyUnavailableException(exception);
        }
    }

    private <T> T deserialize(String value, Class<T> type) {
        if (value == null || value.isBlank()) {
            throw new MediaIdempotencyUnavailableException();
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new MediaIdempotencyUnavailableException(exception);
        }
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, int propagation) {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(propagation);
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return transaction;
    }
}
