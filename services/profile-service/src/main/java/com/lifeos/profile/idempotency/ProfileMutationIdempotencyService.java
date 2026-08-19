package com.lifeos.profile.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.profile.authorization.ProfileSubject;
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
 * Claims a durable retry reservation before a write and atomically commits its immutable public
 * response snapshot with the write. Matching retries deserialize that snapshot rather than reading
 * a later resource version, preserving exact response semantics.
 */
@Service
public class ProfileMutationIdempotencyService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final ProfileMutationIdempotencyRepository repository;
    private final ProfileMutationFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate reservationTransaction;
    private final TransactionTemplate completionTransaction;
    private final TransactionTemplate cleanupTransaction;

    public ProfileMutationIdempotencyService(
            ProfileMutationIdempotencyRepository repository,
            ProfileMutationFingerprint fingerprint,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        reservationTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        completionTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRED);
        cleanupTransaction = transaction(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Applies one mutation or returns an immutable prior response. Authorization must happen before
     * this call, so each retry still observes current bearer/session and policy state.
     */
    public <T> ProfileIdempotencyExecution<T> execute(
            ProfileSubject subject,
            ProfileMutationOperation operation,
            UUID candidateResourceId,
            long expectedVersion,
            String idempotencyKey,
            String requestFingerprint,
            Class<T> responseType,
            Function<UUID, T> completion) {
        String rawKey = ProfileIdempotencyKey.requireValid(idempotencyKey);
        String keyHash = fingerprint.keyHash(rawKey);
        ProfileMutationIdempotency reservation = reserveOrLoad(
                subject, operation, candidateResourceId, keyHash, requestFingerprint, expectedVersion);
        if (!reservation.matchesRequest(requestFingerprint)) {
            throw new ProfileIdempotencyConflictException();
        }
        try {
            return Objects.requireNonNull(completionTransaction.execute(status -> completeOrReplay(
                    reservation.getId(),
                    subject,
                    operation,
                    requestFingerprint,
                    responseType,
                    completion)));
        } catch (ProfileMutationRejectedException exception) {
            discardPendingReservation(subject, operation, idempotencyKey, requestFingerprint);
            throw exception;
        } catch (ProfileIdempotencyConflictException | ProfileIdempotencyUnavailableException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new ProfileIdempotencyUnavailableException(exception);
        }
    }

    /**
     * Removes a failed, uncompleted reservation for a request that was deterministically rejected
     * by a business precondition. The write transaction has already rolled back when this is
     * called, so retaining PENDING would turn a terminal 412 into an unbounded stale retry.
     * A row lock and matching fingerprint ensure a concurrent or differently shaped request is
     * never removed.
     */
    public void discardPendingReservation(
            ProfileSubject subject,
            ProfileMutationOperation operation,
            String idempotencyKey,
            String requestFingerprint) {
        String rawKey = ProfileIdempotencyKey.requireValid(idempotencyKey);
        String keyHash = fingerprint.keyHash(rawKey);
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
            throw new ProfileIdempotencyUnavailableException(exception);
        }
    }

    private <T> ProfileIdempotencyExecution<T> completeOrReplay(
            UUID reservationId,
            ProfileSubject subject,
            ProfileMutationOperation operation,
            String requestFingerprint,
            Class<T> responseType,
            Function<UUID, T> completion) {
        ProfileMutationIdempotency reservation = repository
                .findByIdAndScopeForUpdate(reservationId, subject.accountId(), subject.tenantId(), operation)
                .orElseThrow(ProfileIdempotencyUnavailableException::new);
        if (!reservation.matchesRequest(requestFingerprint)) {
            throw new ProfileIdempotencyConflictException();
        }
        if (reservation.isCompleted()) {
            return new ProfileIdempotencyExecution<>(
                    deserialize(reservation.completedSnapshot(), responseType),
                    true,
                    reservation.completedResponseStatus(),
                    reservation.completedResponseLocation());
        }
        T result = Objects.requireNonNull(completion.apply(reservation.getResourceId()), "completion result must not be null");
        reservation.complete(serialize(result));
        return new ProfileIdempotencyExecution<>(
                result, false, reservation.completedResponseStatus(), reservation.completedResponseLocation());
    }

    private ProfileMutationIdempotency reserveOrLoad(
            ProfileSubject subject,
            ProfileMutationOperation operation,
            UUID candidateResourceId,
            String keyHash,
            String requestFingerprint,
            long expectedVersion) {
        Optional<ProfileMutationIdempotency> existing = findExisting(subject, operation, keyHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return Objects.requireNonNull(reservationTransaction.execute(status -> repository.saveAndFlush(
                    new ProfileMutationIdempotency(
                            subject.accountId(),
                            subject.tenantId(),
                            operation,
                            candidateResourceId,
                            keyHash,
                            requestFingerprint,
                            expectedVersion))));
        } catch (DataIntegrityViolationException exception) {
            return findExisting(subject, operation, keyHash).orElseThrow(ProfileIdempotencyUnavailableException::new);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new ProfileIdempotencyUnavailableException(exception);
        }
    }

    private Optional<ProfileMutationIdempotency> findExisting(
            ProfileSubject subject, ProfileMutationOperation operation, String keyHash) {
        try {
            return repository.findByActorAccountIdAndTenantIdAndOperationAndIdempotencyKeyHash(
                    subject.accountId(), subject.tenantId(), operation, keyHash);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new ProfileIdempotencyUnavailableException(exception);
        }
    }

    private String serialize(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new ProfileIdempotencyUnavailableException(exception);
        }
    }

    private <T> T deserialize(String snapshot, Class<T> responseType) {
        try {
            return objectMapper.readValue(snapshot, responseType);
        } catch (JsonProcessingException exception) {
            throw new ProfileIdempotencyUnavailableException(exception);
        }
    }

    private static TransactionTemplate transaction(PlatformTransactionManager transactionManager, int propagation) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(propagation);
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return transaction;
    }
}
