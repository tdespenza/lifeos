package com.lifeos.calendar.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.calendar.domain.CalendarMutationIdempotency;
import com.lifeos.calendar.domain.CalendarMutationIdempotencyRepository;
import com.lifeos.calendar.domain.CalendarMutationIdempotencyState;
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
 * Claims an independent durable reservation, then joins the caller's write transaction to commit
 * its immutable response snapshot. A pending reservation is removed after deterministic business
 * rejection so a stale retry cannot be held forever.
 */
@Service
public class CalendarMutationIdempotencyService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final CalendarMutationIdempotencyRepository repository;
    private final CalendarMutationFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate reservationTransaction;
    private final TransactionTemplate completionTransaction;
    private final TransactionTemplate cleanupTransaction;

    public CalendarMutationIdempotencyService(
            CalendarMutationIdempotencyRepository repository,
            CalendarMutationFingerprint fingerprint,
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

    /** Executes the supplied successful mutation at most once for the scoped HMAC key. */
    public <T> CalendarIdempotencyResult<T> execute(
            UUID actorAccountId,
            String tenantId,
            CalendarMutationOperation operation,
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
        CalendarMutationIdempotency reservation = reserveOrLoad(
                actorAccountId, tenantId, operation, resourceScope, keyHash, requestHash, expectedVersion);
        if (!reservation.matches(requestHash, expectedVersion)) {
            throw new CalendarIdempotencyConflictException();
        }
        try {
            return Objects.requireNonNull(completionTransaction.execute(status -> completeOrReplay(
                    reservation.getId(), requestHash, expectedVersion, responseType, responseStatus, responseLocation, mutation)));
        } catch (CalendarIdempotencyConflictException | CalendarIdempotencyUnavailableException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new CalendarIdempotencyUnavailableException(exception);
        } catch (RuntimeException exception) {
            discardPendingReservation(actorAccountId, tenantId, operation, resourceScope, keyHash, requestHash, expectedVersion);
            throw exception;
        }
    }

    private <T> CalendarIdempotencyResult<T> completeOrReplay(
            UUID reservationId,
            String requestHash,
            Long expectedVersion,
            Class<T> responseType,
            int responseStatus,
            String responseLocation,
            Supplier<T> mutation) {
        CalendarMutationIdempotency reservation = repository
                .findByIdForUpdate(reservationId)
                .orElseThrow(CalendarIdempotencyUnavailableException::new);
        if (!reservation.matches(requestHash, expectedVersion)) {
            throw new CalendarIdempotencyConflictException();
        }
        if (reservation.getState() == CalendarMutationIdempotencyState.COMPLETED) {
            return new CalendarIdempotencyResult<>(
                    deserialize(reservation.getResponseJson(), responseType),
                    reservation.getResponseStatus(),
                    reservation.getResponseLocation(),
                    true);
        }
        T body = Objects.requireNonNull(mutation.get(), "mutation result must not be null");
        reservation.complete(responseStatus, responseLocation, serialize(body), clock.instant());
        return new CalendarIdempotencyResult<>(body, responseStatus, responseLocation, false);
    }

    private CalendarMutationIdempotency reserveOrLoad(
            UUID actorAccountId,
            String tenantId,
            CalendarMutationOperation operation,
            String resourceScope,
            String keyHash,
            String requestHash,
            Long expectedVersion) {
        CalendarMutationIdempotency existing = findExisting(actorAccountId, tenantId, operation, resourceScope, keyHash);
        if (existing != null) {
            return existing;
        }
        try {
            return Objects.requireNonNull(reservationTransaction.execute(status -> repository.saveAndFlush(
                    CalendarMutationIdempotency.pending(
                            actorAccountId,
                            tenantId,
                            operation.name(),
                            resourceScope,
                            keyHash,
                            requestHash,
                            expectedVersion,
                            clock.instant()))));
        } catch (DataIntegrityViolationException exception) {
            CalendarMutationIdempotency raced = findExisting(actorAccountId, tenantId, operation, resourceScope, keyHash);
            if (raced == null) {
                throw new CalendarIdempotencyUnavailableException(exception);
            }
            return raced;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new CalendarIdempotencyUnavailableException(exception);
        }
    }

    private void discardPendingReservation(
            UUID actorAccountId,
            String tenantId,
            CalendarMutationOperation operation,
            String resourceScope,
            String keyHash,
            String requestHash,
            Long expectedVersion) {
        try {
            cleanupTransaction.executeWithoutResult(status -> repository
                    .findByScopeForUpdate(actorAccountId, tenantId, operation.name(), resourceScope, keyHash)
                    .filter(value -> value.getState() == CalendarMutationIdempotencyState.PENDING)
                    .filter(value -> value.matches(requestHash, expectedVersion))
                    .ifPresent(repository::delete));
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new CalendarIdempotencyUnavailableException(exception);
        }
    }

    private CalendarMutationIdempotency findExisting(
            UUID actorAccountId,
            String tenantId,
            CalendarMutationOperation operation,
            String resourceScope,
            String keyHash) {
        try {
            return repository
                    .findByActorAccountIdAndTenantIdAndOperationAndResourceScopeAndIdempotencyKeyHash(
                            actorAccountId, tenantId, operation.name(), resourceScope, keyHash)
                    .orElse(null);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new CalendarIdempotencyUnavailableException(exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CalendarIdempotencyUnavailableException(exception);
        }
    }

    private <T> T deserialize(String value, Class<T> type) {
        if (value == null || value.isBlank()) {
            throw new CalendarIdempotencyUnavailableException();
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new CalendarIdempotencyUnavailableException(exception);
        }
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, int propagation) {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(propagation);
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return transaction;
    }
}
