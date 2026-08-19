package com.lifeos.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class GatewayRetryPolicyTest {

    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    void usesCappedExponentialFullJitterForSafeMethods() {
        GatewayProperties properties = properties();
        GatewayProperties.Retry retry = properties.getUpstream().getRetry();
        retry.setMaxAttempts(4);
        retry.setInitialBackoff(Duration.ofMillis(100));
        retry.setMaxBackoff(Duration.ofMillis(250));
        retry.setTotalTimeout(Duration.ofSeconds(60));
        AtomicInteger jitterIndex = new AtomicInteger();
        long[] jitterValues = {
            Duration.ofMillis(100).toNanos(),
            Duration.ofMillis(200).toNanos(),
            Duration.ofMillis(250).toNanos()
        };
        GatewayRetryPolicy policy = new GatewayRetryPolicy(
                properties,
                () -> 0L,
                delay -> {},
                () -> jitterValues[jitterIndex.getAndIncrement()]);
        long startedAtNanos = policy.start();

        assertThat(policy.nextRetry(HttpMethod.GET, 1, startedAtNanos))
                .satisfies(decision -> {
                    assertThat(decision.retry()).isTrue();
                    assertThat(decision.delay()).isEqualTo(Duration.ofMillis(100));
                });
        assertThat(policy.nextRetry(HttpMethod.GET, 2, startedAtNanos))
                .satisfies(decision -> {
                    assertThat(decision.retry()).isTrue();
                    assertThat(decision.delay()).isEqualTo(Duration.ofMillis(200));
                });
        assertThat(policy.nextRetry(HttpMethod.GET, 3, startedAtNanos))
                .satisfies(decision -> {
                    assertThat(decision.retry()).isTrue();
                    assertThat(decision.delay()).isEqualTo(Duration.ofMillis(250));
                });
        assertThat(policy.nextRetry(HttpMethod.GET, 4, startedAtNanos))
                .extracting(GatewayRetryPolicy.RetryDecision::skipReason)
                .isEqualTo(GatewayRetryPolicy.RetrySkipReason.MAX_ATTEMPTS_EXHAUSTED);
    }

    @Test
    void doesNotRetryUnsafeMethodEvenWhenAttemptsAndTimeRemain() {
        GatewayProperties properties = properties();
        properties.getUpstream().getRetry().setMaxAttempts(5);
        GatewayRetryPolicy policy = new GatewayRetryPolicy(properties, () -> 0L, delay -> {}, () -> 0L);

        assertThat(policy.nextRetry(HttpMethod.POST, 1, policy.start()))
                .extracting(GatewayRetryPolicy.RetryDecision::skipReason)
                .isEqualTo(GatewayRetryPolicy.RetrySkipReason.UNSAFE_METHOD);
        assertThat(policy.nextRetry(HttpMethod.PUT, 1, policy.start()))
                .extracting(GatewayRetryPolicy.RetryDecision::skipReason)
                .isEqualTo(GatewayRetryPolicy.RetrySkipReason.UNSAFE_METHOD);
    }

    @Test
    void doesNotStartASecondAttemptWhenTheFullDeadlineCannotFit() {
        GatewayProperties properties = properties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(3));
        properties.getUpstream().getRetry().setTotalTimeout(Duration.ofSeconds(6));
        AtomicLong now = new AtomicLong();
        GatewayRetryPolicy policy = new GatewayRetryPolicy(properties, now::get, delay -> {}, () -> 0L);
        long startedAtNanos = policy.start();
        now.set(Duration.ofSeconds(1).toNanos());

        assertThat(policy.nextRetry(HttpMethod.GET, 1, startedAtNanos))
                .extracting(GatewayRetryPolicy.RetryDecision::skipReason)
                .isEqualTo(GatewayRetryPolicy.RetrySkipReason.TOTAL_TIMEOUT_EXHAUSTED);
    }

    @Test
    void interruptionCancelsThePendingRetryAndPreservesTheInterruptStatus() {
        GatewayRetryPolicy policy = new GatewayRetryPolicy(properties(), () -> 0L, delay -> {
            throw new InterruptedException("test interruption");
        }, () -> 0L);

        assertThat(policy.await(Duration.ofMillis(1))).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }
}
