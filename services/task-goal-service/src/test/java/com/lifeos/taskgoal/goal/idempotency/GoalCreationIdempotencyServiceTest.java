package com.lifeos.taskgoal.goal.idempotency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionTimedOutException;

/** Ensures finite reservation/completion transaction timeouts remain safely retryable at the API. */
@ExtendWith(MockitoExtension.class)
class GoalCreationIdempotencyServiceTest {

    @Mock
    private GoalCreationIdempotencyTransactions transactions;

    private GoalCreationIdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new GoalCreationIdempotencyService(transactions);
    }

    @Test
    void reservationTimeoutBecomesSafeRetryableUnavailability() {
        when(transactions.findExisting(any(UUID.class), anyString(), anyString())).thenReturn(Optional.empty());
        when(transactions.reserve(any(UUID.class), anyString(), anyString(), anyString(), any(UUID.class)))
                .thenThrow(new TransactionTimedOutException("reservation timed out"));

        assertThatThrownBy(() -> service.createOrReplay(
                        UUID.randomUUID(),
                        UUID.randomUUID().toString(),
                        "reservation-timeout-key",
                        "Title",
                        UUID.randomUUID()))
                .isInstanceOf(GoalIdempotencyUnavailableException.class);
    }

    @Test
    void lookupTimeoutBecomesSafeRetryableUnavailability() {
        when(transactions.findExisting(any(UUID.class), anyString(), anyString()))
                .thenThrow(new TransactionTimedOutException("lookup timed out"));

        assertThatThrownBy(() -> service.createOrReplay(
                        UUID.randomUUID(),
                        UUID.randomUUID().toString(),
                        "lookup-timeout-key",
                        "Title",
                        UUID.randomUUID()))
                .isInstanceOf(GoalIdempotencyUnavailableException.class);
    }

    @Test
    void completionTimeoutBecomesSafeRetryableUnavailability() {
        UUID accountId = UUID.randomUUID();
        String tenantId = accountId.toString();
        String key = "completion-timeout-key";
        String title = "Title";
        GoalCreationIdempotency existing = new GoalCreationIdempotency(
                accountId,
                tenantId,
                GoalCreationFingerprint.keyHash(key),
                GoalCreationFingerprint.requestFingerprint(title),
                UUID.randomUUID());
        when(transactions.findExisting(
                        eq(accountId), eq(tenantId), eq(GoalCreationFingerprint.keyHash(key))))
                .thenReturn(Optional.of(existing));
        when(transactions.complete(
                        eq(existing.getId()),
                        eq(accountId),
                        eq(tenantId),
                        eq(GoalCreationFingerprint.requestFingerprint(title)),
                        eq(title)))
                .thenThrow(new TransactionTimedOutException("completion timed out"));

        assertThatThrownBy(() -> service.createOrReplay(
                        accountId, tenantId, key, title, UUID.randomUUID()))
                .isInstanceOf(GoalIdempotencyUnavailableException.class);
    }
}
