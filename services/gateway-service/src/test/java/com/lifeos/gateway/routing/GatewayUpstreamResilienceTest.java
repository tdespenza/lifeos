package com.lifeos.gateway.routing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.lifeos.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GatewayUpstreamResilienceTest {

    private static final GatewayRoute ROUTE = new GatewayRoute(
            "goals", "/api/v1/goals", URI.create("https://task-goal.test"), false, Set.of());

    @Test
    void rejectsWithoutWaitingWhenTheRouteBulkheadIsFull() {
        GatewayProperties properties = properties(1, 5);
        GatewayUpstreamResilience resilience = new GatewayUpstreamResilience(
                properties, new SimpleMeterRegistry());

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
        GatewayUpstreamResilience resilience = new GatewayUpstreamResilience(
                properties, new SimpleMeterRegistry());

        fail(resilience);
        fail(resilience);

        assertThatThrownBy(() -> resilience.acquire(ROUTE))
                .isInstanceOf(GatewayUpstreamException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                                ((GatewayUpstreamException) error).getFailureClass())
                        .isEqualTo("circuit_open"));

        try {
            Thread.sleep(70);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        GatewayUpstreamResilience.Permit probe = resilience.acquire(ROUTE);
        try {
            assertThatThrownBy(() -> resilience.acquire(ROUTE))
                    .isInstanceOf(GatewayUpstreamException.class)
                    .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                                    ((GatewayUpstreamException) error).getFailureClass())
                            .isEqualTo("circuit_open"));
        } finally {
            probe.recordSuccess();
            probe.close();
        }
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
}
