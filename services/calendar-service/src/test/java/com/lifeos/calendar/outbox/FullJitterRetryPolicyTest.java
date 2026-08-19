package com.lifeos.calendar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class FullJitterRetryPolicyTest {

    @Test
    void capsHighAttemptBackoffWithoutOverflow() {
        FullJitterRetryPolicy policy = new FullJitterRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                RandomGeneratorFactory.of("L64X128MixRandom").create(42L));

        Duration delay = policy.nextDelay(100);

        assertThat(delay).isBetween(Duration.ZERO, Duration.ofMinutes(5));
    }
}
