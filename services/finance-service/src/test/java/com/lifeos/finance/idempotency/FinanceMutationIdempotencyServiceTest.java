package com.lifeos.finance.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class FinanceMutationIdempotencyServiceTest {

    @Test
    void deadlockBackoffUsesPositiveCappedExponentialFullJitter() {
        long firstCap = FinanceMutationIdempotencyService.DEADLOCK_RETRY_DELAY_MILLIS;
        long secondCap = firstCap * 2L;

        for (int sample = 0; sample < 128; sample++) {
            assertThat(FinanceMutationIdempotencyService.deadlockRetryDelayMillis(0))
                    .isBetween(1L, firstCap);
            assertThat(FinanceMutationIdempotencyService.deadlockRetryDelayMillis(1))
                    .isBetween(1L, secondCap);
        }
    }

    @Test
    void deadlockBackoffRejectsAttemptsThatHaveNoFollowingRetry() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FinanceMutationIdempotencyService.deadlockRetryDelayMillis(-1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FinanceMutationIdempotencyService.deadlockRetryDelayMillis(2));
    }
}
