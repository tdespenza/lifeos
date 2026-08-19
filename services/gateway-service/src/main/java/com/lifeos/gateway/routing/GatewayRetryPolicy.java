package com.lifeos.gateway.routing;

import com.lifeos.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Computes bounded retry decisions for gateway upstream calls.
 *
 * <p>The gateway can prove that only {@code GET}, {@code HEAD}, and {@code OPTIONS} requests are
 * safe to replay without an upstream-specific idempotency contract. It deliberately does not infer
 * retry safety for {@code POST}, {@code PUT}, {@code PATCH}, or {@code DELETE}: HTTP method names
 * alone do not prove that a particular downstream implementation has made the operation
 * replay-safe. Domain services that accept idempotency keys own retries for those operations.
 *
 * <p>Retry delays use capped exponential full jitter. Before scheduling a retry, the policy
 * reserves the configured worst-case delay plus one fully bounded outbound call from the logical
 * request's total timeout budget. This means a retry is never started when its configured deadline
 * could exceed that request's budget. The caller keeps the route bulkhead permit for the entire
 * retry sequence, so retries cannot bypass per-route admission limits.
 */
@Component
public final class GatewayRetryPolicy {

    private static final Set<HttpMethod> AUTOMATICALLY_REPLAY_SAFE_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final GatewayProperties.Retry properties;
    private final Duration perAttemptTimeout;
    private final LongSupplier nanoTime;
    private final RetrySleeper sleeper;
    private final LongSupplier jitterSource;

    /**
     * Creates the production retry policy.
     *
     * @param gatewayProperties validated gateway configuration
     */
    @Autowired
    public GatewayRetryPolicy(GatewayProperties gatewayProperties) {
        this(
                gatewayProperties,
                System::nanoTime,
                Thread::sleep,
                () -> ThreadLocalRandom.current().nextLong());
    }

    GatewayRetryPolicy(
            GatewayProperties gatewayProperties,
            LongSupplier nanoTime,
            RetrySleeper sleeper,
            LongSupplier jitterSource) {
        this.properties = gatewayProperties.getUpstream().getRetry();
        this.perAttemptTimeout = fullAttemptTimeout(
                gatewayProperties.getConnectTimeout(), gatewayProperties.getReadTimeout());
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.jitterSource = jitterSource;
    }

    /**
     * Starts the total timeout budget for one logical upstream invocation.
     *
     * @return monotonic start time for this invocation
     */
    long start() {
        return nanoTime.getAsLong();
    }

    /**
     * Decides whether a completed transient failure may be retried.
     *
     * @param method proxied HTTP method
     * @param completedAttempts number of upstream calls already completed
     * @param startedAtNanos logical invocation start returned by {@link #start()}
     * @return retry decision, including the randomized bounded delay when admitted
     */
    RetryDecision nextRetry(HttpMethod method, int completedAttempts, long startedAtNanos) {
        if (!AUTOMATICALLY_REPLAY_SAFE_METHODS.contains(method)) {
            return RetryDecision.skip(RetrySkipReason.UNSAFE_METHOD);
        }
        if (completedAttempts >= properties.getMaxAttempts()) {
            return RetryDecision.skip(RetrySkipReason.MAX_ATTEMPTS_EXHAUSTED);
        }

        long maximumDelayNanos = exponentialDelayCap(completedAttempts);
        long remainingNanos = remainingNanos(startedAtNanos);
        long requiredNanos = saturatingAdd(maximumDelayNanos, perAttemptTimeout.toNanos());
        if (remainingNanos <= requiredNanos) {
            return RetryDecision.skip(RetrySkipReason.TOTAL_TIMEOUT_EXHAUSTED);
        }

        return RetryDecision.retry(Duration.ofNanos(fullJitter(maximumDelayNanos)));
    }

    /**
     * Sleeps for one bounded retry delay.
     *
     * <p>Interrupting the request thread cancels the retry and preserves the interrupt status. The
     * caller returns or propagates the preceding upstream outcome instead of starting another call.
     *
     * @param delay scheduled retry delay
     * @return {@code true} when the retry may proceed, otherwise {@code false}
     */
    boolean await(Duration delay) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (delay.isZero()) {
            return true;
        }
        try {
            sleeper.sleep(delay);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long remainingNanos(long startedAtNanos) {
        long elapsedNanos = nanoTime.getAsLong() - startedAtNanos;
        if (elapsedNanos <= 0) {
            return properties.getTotalTimeout().toNanos();
        }
        long totalTimeoutNanos = properties.getTotalTimeout().toNanos();
        return elapsedNanos >= totalTimeoutNanos ? 0 : totalTimeoutNanos - elapsedNanos;
    }

    private long exponentialDelayCap(int completedAttempts) {
        long capNanos = properties.getInitialBackoff().toNanos();
        long maxNanos = properties.getMaxBackoff().toNanos();
        for (int retryOrdinal = 1; retryOrdinal < completedAttempts && capNanos < maxNanos; retryOrdinal++) {
            capNanos = Math.min(maxNanos, saturatingMultiplyByTwo(capNanos));
        }
        return capNanos;
    }

    private long fullJitter(long maximumDelayNanos) {
        if (maximumDelayNanos == 0) {
            return 0;
        }
        long nonNegativeRandom = jitterSource.getAsLong() & Long.MAX_VALUE;
        return nonNegativeRandom % (maximumDelayNanos + 1);
    }

    private static Duration fullAttemptTimeout(Duration connectTimeout, Duration readTimeout) {
        return connectTimeout.plus(readTimeout);
    }

    private static long saturatingAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static long saturatingMultiplyByTwo(long value) {
        return value > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : value * 2;
    }

    /** Retry decision result with a client-safe reason when no retry is admitted. */
    record RetryDecision(boolean retry, Duration delay, RetrySkipReason skipReason) {

        private static RetryDecision retry(Duration delay) {
            return new RetryDecision(true, delay, null);
        }

        private static RetryDecision skip(RetrySkipReason reason) {
            return new RetryDecision(false, Duration.ZERO, reason);
        }
    }

    /** Reason why a transient upstream outcome was not replayed. */
    enum RetrySkipReason {
        UNSAFE_METHOD,
        MAX_ATTEMPTS_EXHAUSTED,
        TOTAL_TIMEOUT_EXHAUSTED
    }

    /** Injectable interruption-aware wait primitive used to keep retry tests deterministic. */
    @FunctionalInterface
    interface RetrySleeper {

        void sleep(Duration delay) throws InterruptedException;
    }
}
