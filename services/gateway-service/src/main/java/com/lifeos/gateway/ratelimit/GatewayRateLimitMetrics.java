package com.lifeos.gateway.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality gateway rate-limit telemetry. Route identifiers are deployment-owned; client,
 * account, token, and address values are intentionally never metric labels.
 */
@Component
public class GatewayRateLimitMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<ConfiguredLimitKey, AtomicInteger> configuredLimits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> allowedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> unavailableCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> latencyTimers = new ConcurrentHashMap<>();

    public GatewayRateLimitMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records one configured route budget as a Prometheus gauge.
     *
     * @param routeId deployment-owned route identifier
     * @param stage address or validated-account admission stage
     * @param limit configured requests-per-window budget
     */
    public void recordLimit(String routeId, AdmissionStage stage, int limit) {
        String route = routeTag(routeId);
        ConfiguredLimitKey key = new ConfiguredLimitKey(route, stage);
        AtomicInteger value = configuredLimits.computeIfAbsent(key, ignored -> {
            AtomicInteger holder = new AtomicInteger(limit);
            Gauge.builder("gateway.rate.limit", holder, AtomicInteger::get)
                    .description("Configured Redis request budget per gateway route")
                    .tag("route", route)
                    .tag("stage", stage.tagValue)
                    .register(meterRegistry);
            return holder;
        });
        value.set(limit);
    }

    /** Records one rate-limit decision admitted by Redis. */
    public void recordAllowed(String routeId) {
        counter(allowedCounters, "gateway.rate.limit.allowed", routeId,
                "Rate-limit decisions admitted by the gateway Redis rate limiter").increment();
    }

    /** Records one rate-limit decision rejected because its Redis counter exceeded the route budget. */
    public void recordRejected(String routeId) {
        counter(rejectedCounters, "gateway.rate.limit.rejections", routeId,
                "Rate-limit decisions rejected by the gateway Redis rate limiter").increment();
    }

    /** Records one rate-limit decision that Redis could not make safely. */
    public void recordUnavailable(String routeId) {
        counter(unavailableCounters, "gateway.rate.limit.unavailable", routeId,
                "Rate-limit decisions unavailable because Redis failed").increment();
    }

    /** Records the complete Redis decision latency. */
    public void recordLatency(String routeId, long startNanos) {
        timer(routeId).record(
                System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private Counter counter(
            ConcurrentMap<String, Counter> counters, String name, String routeId, String description) {
        String route = routeTag(routeId);
        return counters.computeIfAbsent(route, ignored -> Counter.builder(name)
                .description(description)
                .tag("route", route)
                .register(meterRegistry));
    }

    private Timer timer(String routeId) {
        String route = routeTag(routeId);
        return latencyTimers.computeIfAbsent(route, ignored -> Timer.builder("gateway.rate.limit.latency")
                .description("Redis-backed gateway rate-limit decision latency")
                .tag("route", route)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    private static String routeTag(String routeId) {
        return routeId == null || routeId.isBlank() ? "unknown" : routeId;
    }

    /** Low-cardinality identity used to distinguish configured admission budgets. */
    public enum AdmissionStage {
        ADDRESS("address"),
        ACCOUNT("account");

        private final String tagValue;

        AdmissionStage(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    private record ConfiguredLimitKey(String route, AdmissionStage stage) {
    }
}
