package com.lifeos.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.lifeos.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GatewayUpstreamResilienceTest {

    private static final GatewayRoute ROUTE = new GatewayRoute(
            "goals", "/api/v1/goals", URI.create("https://task-goal.test"), false, Set.of());

    @Test
    void rejectsWithoutWaitingWhenTheRouteBulkheadIsFull() {
        GatewayProperties properties = properties(1, 5);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayUpstreamResilience resilience = new GatewayUpstreamResilience(properties, registry);

        GatewayUpstreamResilience.Permit first = resilience.acquire(ROUTE);
        try {
            assertThatThrownBy(() -> resilience.acquire(ROUTE))
                    .isInstanceOf(GatewayUpstreamException.class)
                    .satisfies(error -> {
                        GatewayUpstreamException exception = (GatewayUpstreamException) error;
                        org.assertj.core.api.Assertions.assertThat(exception.getStatus())
                                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                        org.assertj.core.api.Assertions.assertThat(exception.getFailureClass())
                                .isEqualTo("bulkhead_rejected");
                    });
            assertThat(registry.get("gateway.upstream.bulkhead.rejections")
                    .tag("route", "goals")
                    .counter()
                    .count()).isEqualTo(1.0);
        } finally {
            first.recordSuccess();
            first.close();
        }

        assertThatCode(() -> {
            GatewayUpstreamResilience.Permit permit = resilience.acquire(ROUTE);
            permit.recordSuccess();
            permit.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void opensAfterConsecutiveFailuresAndAllowsOnlyOneHalfOpenProbe() {
        GatewayProperties properties = properties(2, 2);
        properties.getUpstream().getCircuitBreaker().setOpenDuration(Duration.ofMillis(50));
        MutableNanoClock clock = new MutableNanoClock();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayUpstreamResilience resilience = new GatewayUpstreamResilience(properties, registry, clock);

        fail(resilience);
        fail(resilience);

        assertThatThrownBy(() -> resilience.acquire(ROUTE))
                .isInstanceOf(GatewayUpstreamException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                                ((GatewayUpstreamException) error).getFailureClass())
                        .isEqualTo("circuit_open"));
        assertThat(registry.get("gateway.upstream.circuit.open")
                .tag("route", "goals")
                .counter()
                .count()).isEqualTo(1.0);

        clock.advance(Duration.ofMillis(51));
        GatewayUpstreamResilience.Permit probe = resilience.acquire(ROUTE);
        try {
            assertThatThrownBy(() -> resilience.acquire(ROUTE))
                    .isInstanceOf(GatewayUpstreamException.class)
                    .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                                    ((GatewayUpstreamException) error).getFailureClass())
                            .isEqualTo("circuit_open"));
            assertThat(registry.get("gateway.upstream.circuit.open")
                    .tag("route", "goals")
                    .counter()
                    .count()).isEqualTo(2.0);
        } finally {
            probe.recordSuccess();
            probe.close();
        }
    }

    @Test
    void treatsAClosedHalfOpenProbeWithoutOutcomeAsAFailure() {
        GatewayProperties properties = properties(2, 1);
        properties.getUpstream().getCircuitBreaker().setOpenDuration(Duration.ofMillis(50));
        MutableNanoClock clock = new MutableNanoClock();
        GatewayUpstreamResilience resilience = new GatewayUpstreamResilience(
                properties, new SimpleMeterRegistry(), clock);

        fail(resilience);

        clock.advance(Duration.ofMillis(51));
        GatewayUpstreamResilience.Permit probe = resilience.acquire(ROUTE);
        probe.close();

        assertThatThrownBy(() -> resilience.acquire(ROUTE))
                .isInstanceOf(GatewayUpstreamException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                                ((GatewayUpstreamException) error).getFailureClass())
                        .isEqualTo("circuit_open"));

        clock.advance(Duration.ofMillis(51));
        GatewayUpstreamResilience.Permit retry = resilience.acquire(ROUTE);
        retry.recordSuccess();
        retry.close();
    }

    @Test
    void expiresAnUnfinishedHalfOpenProbeBeforeAdmittingAFreshProbe() {
        GatewayProperties properties = properties(2, 1);
        properties.getUpstream().getCircuitBreaker().setOpenDuration(Duration.ofMillis(50));
        MutableNanoClock clock = new MutableNanoClock();
        GatewayUpstreamResilience resilience = new GatewayUpstreamResilience(
                properties, new SimpleMeterRegistry(), clock);

        fail(resilience);

        clock.advance(Duration.ofMillis(51));
        GatewayUpstreamResilience.Permit staleProbe = resilience.acquire(ROUTE);
        clock.advance(Duration.ofMillis(51));

        assertThatCode(() -> {
            GatewayUpstreamResilience.Permit freshProbe = resilience.acquire(ROUTE);
            freshProbe.recordSuccess();
            freshProbe.close();
        }).doesNotThrowAnyException();
        staleProbe.close();
    }

    @Test
    void resetsClosedFailureCountAfterASuccess() {
        GatewayProperties properties = properties(2, 2);
        GatewayUpstreamResilience resilience = new GatewayUpstreamResilience(
                properties, new SimpleMeterRegistry());

        GatewayUpstreamResilience.Permit firstFailure = resilience.acquire(ROUTE);
        firstFailure.recordFailure();
        firstFailure.close();

        GatewayUpstreamResilience.Permit success = resilience.acquire(ROUTE);
        success.recordSuccess();
        success.close();

        GatewayUpstreamResilience.Permit secondFailure = resilience.acquire(ROUTE);
        secondFailure.recordFailure();
        secondFailure.close();

        assertThatCode(() -> {
            GatewayUpstreamResilience.Permit admitted = resilience.acquire(ROUTE);
            admitted.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void ignoresAStalePermitWhileAHalfOpenProbeOwnsTheCircuitTransition() {
        GatewayProperties properties = properties(2, 1);
        properties.getUpstream().getCircuitBreaker().setOpenDuration(Duration.ofMillis(50));
        MutableNanoClock clock = new MutableNanoClock();
        GatewayUpstreamResilience resilience = new GatewayUpstreamResilience(
                properties, new SimpleMeterRegistry(), clock);

        GatewayUpstreamResilience.Permit stalePermit = resilience.acquire(ROUTE);
        GatewayUpstreamResilience.Permit opener = resilience.acquire(ROUTE);
        opener.recordFailure();
        opener.close();

        clock.advance(Duration.ofMillis(51));
        GatewayUpstreamResilience.Permit probe = resilience.acquire(ROUTE);
        stalePermit.recordSuccess();
        stalePermit.close();

        assertThatThrownBy(() -> resilience.acquire(ROUTE))
                .isInstanceOf(GatewayUpstreamException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                                ((GatewayUpstreamException) error).getFailureClass())
                        .isEqualTo("circuit_open"));

        probe.recordSuccess();
        probe.close();
        assertThatCode(() -> {
            GatewayUpstreamResilience.Permit permit = resilience.acquire(ROUTE);
            permit.close();
        }).doesNotThrowAnyException();
    }

    private static void fail(GatewayUpstreamResilience resilience) {
        GatewayUpstreamResilience.Permit permit = resilience.acquire(ROUTE);
        permit.recordFailure();
        permit.close();
    }

    private static GatewayProperties properties(int maxConcurrentRequests, int failureThreshold) {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(List.of(new GatewayProperties.Route(
                "goals", "/api/v1/goals", "https://task-goal.test")));
        properties.getUpstream().getBulkhead().setMaxConcurrentRequests(maxConcurrentRequests);
        properties.getUpstream().getCircuitBreaker().setFailureThreshold(failureThreshold);
        return properties;
    }

    private static final class MutableNanoClock implements java.util.function.LongSupplier {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long getAsLong() {
            return nanos.get();
        }

        private void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
