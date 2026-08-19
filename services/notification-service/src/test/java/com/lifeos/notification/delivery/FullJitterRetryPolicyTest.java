package com.lifeos.notification.delivery;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FullJitterRetryPolicyTest {

    @Test
    void staysWithinCappedExponentialBounds() {
        FullJitterRetryPolicy policy = new FullJitterRetryPolicy(Duration.ofMillis(100), Duration.ofMillis(500), new Random(7));

        assertTrue(policy.nextDelay(1).compareTo(Duration.ofMillis(100)) <= 0);
        assertTrue(policy.nextDelay(2).compareTo(Duration.ofMillis(200)) <= 0);
        assertTrue(policy.nextDelay(3).compareTo(Duration.ofMillis(400)) <= 0);
        assertTrue(policy.nextDelay(100).compareTo(Duration.ofMillis(500)) <= 0);
    }

    @Test
    void rejectsAnInvalidAttemptOrBackoffRange() {
        FullJitterRetryPolicy policy = new FullJitterRetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), new Random(1));

        assertThrows(IllegalArgumentException.class, () -> policy.nextDelay(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FullJitterRetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), new Random(1)));
    }
}
