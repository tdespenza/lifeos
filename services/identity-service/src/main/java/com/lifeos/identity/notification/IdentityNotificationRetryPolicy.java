package com.lifeos.identity.notification;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** Capped full-jitter retry delays for the recovery notification relay. */
@Component
public class IdentityNotificationRetryPolicy {

    private final IdentityRecoveryNotificationProperties properties;

    public IdentityNotificationRetryPolicy(IdentityRecoveryNotificationProperties properties) {
        this.properties = properties;
    }

    public Duration nextDelay(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 20));
        long initialMillis = properties.getInitialBackoff().toMillis();
        long maxMillis = properties.getMaxBackoff().toMillis();
        long scaled;
        try {
            scaled = Math.multiplyExact(initialMillis, 1L << exponent);
        } catch (ArithmeticException exception) {
            scaled = maxMillis;
        }
        long bound = Math.min(maxMillis, Math.max(initialMillis, scaled));
        long delay = ThreadLocalRandom.current().nextLong(bound + 1);
        return Duration.ofMillis(delay);
    }
}
