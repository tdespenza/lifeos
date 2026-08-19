package com.lifeos.finance.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.finance.authorization.FinanceSubject;
import com.lifeos.finance.service.FinanceBudgetOverlapException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates durable caller-scoped reservations, commits their exact immutable response with the
 * mutation, and deserializes that original snapshot on a matching retry.
 */
@Service
public class FinanceMutationIdempotencyService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
    private static final int MAX_DEADLOCK_RETRIES = 3;
    static final long DEADLOCK_RETRY_DELAY_MILLIS = 25L;

    private final FinanceMutationIdempotencyRepository repository;
    private final FinanceMutationFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate reservationTransaction;
    private final TransactionTemplate completionTransaction;
    private final TransactionTemplate cleanupTransaction;
    private final Counter deadlockRetries;
    private final Counter deadlockExhausted;

    /** Compatibility constructor for isolated unit tests that do not provide a metrics registry. */
    public FinanceMutationIdempotencyService(
            FinanceMutationIdempotencyRepository repository,
            FinanceMutationFingerprint fingerprint,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(repository, fingerprint, objectMapper, transactionManager, new SimpleMeterRegistry());
    }

    @Autowired
    public FinanceMutationIdempotencyService(
            FinanceMutationIdempotencyRepository repository,
            FinanceMutationFingerprint fingerprint,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        reservationTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        completionTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRED);
        cleanupTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        deadlockRetries = Counter.builder("finance.idempotency.deadlock.retries")
                .description("Bounded retries of PostgreSQL deadlock-aborted Finance mutations")
                .register(meterRegistry);
        deadlockExhausted = Counter.builder("finance.idempotency.deadlock.exhausted")
                .description("Finance mutations that exhausted PostgreSQL deadlock retries")
                .register(meterRegistry);
    }

    /** Authorization must happen before this call so retries cannot bypass current session/policy state. */
    public <T> FinanceIdempotencyExecution<T> execute(
            FinanceSubject subject,
            FinanceMutationOperation operation,
            UUID candidateResourceId,
            long expectedVersion,
            String idempotencyKey,
            String requestFingerprint,
            Class<T> responseType,
            Function<UUID, T> completion) {
        String rawKey = FinanceIdempotencyKey.requireValid(idempotencyKey);
        String keyHash = fingerprint.keyHash(rawKey);
        FinanceMutationIdempotency reservation = reserveOrLoad(
                subject, operation, candidateResourceId, keyHash, requestFingerprint, expectedVersion);
        if (!reservation.matchesRequest(requestFingerprint)) {
            throw new FinanceIdempotencyConflictException();
        }
        try {
            return completeWithDeadlockRetry(
                    reservation, subject, operation, requestFingerprint, responseType, completion);
        } catch (FinanceMutationRejectedException exception) {
            discardPendingReservation(subject, operation, rawKey, requestFingerprint);
            throw exception;
        } catch (FinanceIdempotencyConflictException | FinanceIdempotencyUnavailableException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            FinanceMutationRejectedException rejected = rejectedCause(exception);
            if (rejected != null) {
                discardPendingReservation(subject, operation, rawKey, requestFingerprint);
                throw rejected;
            }
            if (isBudgetOverlapConstraint(operation, exception)) {
                discardPendingReservation(subject, operation, rawKey, requestFingerprint);
                throw new FinanceBudgetOverlapException(exception);
            }
            throw new FinanceIdempotencyUnavailableException(exception);
        }
    }

    /**
     * PostgreSQL can abort concurrent exclusion-constraint checks with SQLSTATE 40P01 before either
     * request observes the committed overlap. Retrying the whole completion transaction is safe:
     * the durable reservation is still PENDING, and the next attempt either observes the winner
     * and returns the deterministic overlap response or completes this request. The retry count
     * and delay are deliberately bounded; an exhausted transient remains retryable through the
     * original idempotency key rather than being converted into a false business conflict.
     */
    private <T> FinanceIdempotencyExecution<T> completeWithDeadlockRetry(
            FinanceMutationIdempotency reservation,
            FinanceSubject subject,
            FinanceMutationOperation operation,
            String requestFingerprint,
            Class<T> responseType,
            Function<UUID, T> completion) {
        for (int attempt = 0; ; attempt++) {
            try {
                return Objects.requireNonNull(completionTransaction.execute(status -> completeOrReplay(
                        reservation.getId(), subject, operation, requestFingerprint, responseType, completion)));
            } catch (DataAccessException | TransactionTimedOutException exception) {
                if (!isPostgresDeadlock(exception) || attempt >= MAX_DEADLOCK_RETRIES - 1) {
                    if (isPostgresDeadlock(exception)) {
                        deadlockExhausted.increment();
                    }
                    throw exception;
                }
                deadlockRetries.increment();
                try {
                    Thread.sleep(deadlockRetryDelayMillis(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new FinanceIdempotencyUnavailableException(interrupted);
                }
            }
        }
    }

    /**
     * Computes a capped exponential full-jitter delay for the next deadlock attempt. The retry
     * count is tiny and the cap is deliberately small so a transient exclusion-index deadlock
     * cannot turn one request into an unbounded wait or synchronized retry storm.
     */
    static long deadlockRetryDelayMillis(int completedAttempt) {
        if (completedAttempt < 0 || completedAttempt >= MAX_DEADLOCK_RETRIES - 1) {
            throw new IllegalArgumentException("completedAttempt is outside the retry window");
        }
        long cap = Math.min(1_000L, DEADLOCK_RETRY_DELAY_MILLIS << completedAttempt);
        return ThreadLocalRandom.current().nextLong(1L, cap + 1L);
    }

    /** Removes only a matching terminal business-rejection reservation, avoiding stale PENDING rows. */
    public void discardPendingReservation(
            FinanceSubject subject,
            FinanceMutationOperation operation,
            String idempotencyKey,
            String requestFingerprint) {
        String keyHash = fingerprint.keyHash(FinanceIdempotencyKey.requireValid(idempotencyKey));
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
            throw new FinanceIdempotencyUnavailableException(exception);
        }
    }

    private <T> FinanceIdempotencyExecution<T> completeOrReplay(
            UUID reservationId,
            FinanceSubject subject,
            FinanceMutationOperation operation,
            String requestFingerprint,
            Class<T> responseType,
            Function<UUID, T> completion) {
        FinanceMutationIdempotency reservation = repository
                .findByIdAndScopeForUpdate(reservationId, subject.accountId(), subject.tenantId(), operation)
                .orElseThrow(FinanceIdempotencyUnavailableException::new);
        if (!reservation.matchesRequest(requestFingerprint)) {
            throw new FinanceIdempotencyConflictException();
        }
        if (reservation.isCompleted()) {
            return new FinanceIdempotencyExecution<>(
                    deserialize(reservation.completedSnapshot(), responseType),
                    true,
                    reservation.completedResponseStatus(),
                    reservation.completedResponseLocation());
        }
        T result = Objects.requireNonNull(completion.apply(reservation.getResourceId()), "completion result must not be null");
        reservation.complete(serialize(result));
        return new FinanceIdempotencyExecution<>(
                result, false, reservation.completedResponseStatus(), reservation.completedResponseLocation());
    }

    private FinanceMutationIdempotency reserveOrLoad(
            FinanceSubject subject,
            FinanceMutationOperation operation,
            UUID candidateResourceId,
            String keyHash,
            String requestFingerprint,
            long expectedVersion) {
        Optional<FinanceMutationIdempotency> existing = findExisting(subject, operation, keyHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return Objects.requireNonNull(reservationTransaction.execute(status -> repository.saveAndFlush(
                    new FinanceMutationIdempotency(
                            subject.accountId(),
                            subject.tenantId(),
                            operation,
                            candidateResourceId,
                            keyHash,
                            requestFingerprint,
                            expectedVersion))));
        } catch (DataIntegrityViolationException exception) {
            return findExisting(subject, operation, keyHash).orElseThrow(FinanceIdempotencyUnavailableException::new);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new FinanceIdempotencyUnavailableException(exception);
        }
    }

    private Optional<FinanceMutationIdempotency> findExisting(
            FinanceSubject subject, FinanceMutationOperation operation, String keyHash) {
        try {
            return repository.findByActorAccountIdAndTenantIdAndOperationAndIdempotencyKeyHash(
                    subject.accountId(), subject.tenantId(), operation, keyHash);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new FinanceIdempotencyUnavailableException(exception);
        }
    }

    private String serialize(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new FinanceIdempotencyUnavailableException(exception);
        }
    }

    private <T> T deserialize(String snapshot, Class<T> responseType) {
        try {
            return objectMapper.readValue(snapshot, responseType);
        } catch (JsonProcessingException exception) {
            throw new FinanceIdempotencyUnavailableException(exception);
        }
    }

    private static TransactionTemplate transaction(PlatformTransactionManager transactionManager, int propagation) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(propagation);
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return transaction;
    }

    private static FinanceMutationRejectedException rejectedCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof FinanceMutationRejectedException rejected) {
                return rejected;
            }
            current = current.getCause();
        }
        return null;
    }

    /** PostgreSQL exclusion violations have SQLSTATE 23P01; the constraint name is a safe fallback. */
    private static boolean isBudgetOverlapConstraint(FinanceMutationOperation operation, Throwable exception) {
        if (operation != FinanceMutationOperation.CREATE_BUDGET && operation != FinanceMutationOperation.UPDATE_BUDGET) {
            return false;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException && "23P01".equals(sqlException.getSQLState())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("ex_finance_budget_owner_tenant_category_period")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isPostgresDeadlock(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException && "40P01".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
