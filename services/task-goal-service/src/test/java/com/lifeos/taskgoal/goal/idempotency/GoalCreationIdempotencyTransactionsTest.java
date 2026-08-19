package com.lifeos.taskgoal.goal.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** Guards the finite transaction bounds that protect reservation and replay completion. */
class GoalCreationIdempotencyTransactionsTest {

    @Test
    void reservationLookupAndCompletionHaveTheSameFiniteTransactionTimeout() throws Exception {
        Transactional reservation = GoalCreationIdempotencyTransactions.class
                .getMethod("reserve", UUID.class, String.class, String.class, String.class, UUID.class)
                .getAnnotation(Transactional.class);
        Transactional lookup = GoalCreationIdempotencyTransactions.class
                .getMethod("findExisting", UUID.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional completion = GoalCreationIdempotencyTransactions.class
                .getMethod("complete", UUID.class, UUID.class, String.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(reservation).isNotNull();
        assertThat(lookup).isNotNull();
        assertThat(completion).isNotNull();
        assertThat(reservation.timeout())
                .isEqualTo(GoalCreationIdempotencyTransactions.TRANSACTION_TIMEOUT_SECONDS);
        assertThat(lookup.timeout())
                .isEqualTo(GoalCreationIdempotencyTransactions.TRANSACTION_TIMEOUT_SECONDS);
        assertThat(lookup.readOnly()).isTrue();
        assertThat(completion.timeout())
                .isEqualTo(GoalCreationIdempotencyTransactions.TRANSACTION_TIMEOUT_SECONDS);
    }
}
