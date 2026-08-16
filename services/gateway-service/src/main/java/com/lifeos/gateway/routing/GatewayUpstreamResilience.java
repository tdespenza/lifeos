package com.lifeos.gateway.routing;

import com.lifeos.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-route upstream bulkheads and consecutive-failure circuit breakers.
 *
 * <p>Admission never waits for a permit. A request either acquires the route's bounded bulkhead
 * immediately or receives a controlled degraded response, so slow upstreams cannot create an
 * unbounded queue in the gateway. Circuit state is local operational state: it protects one
 * gateway instance and is deliberately not treated as authorization or business state.
 */
@Component
public class GatewayUpstreamResilience {

    private final GatewayProperties.Upstream properties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, RouteState> states = new ConcurrentHashMap<>();

    public GatewayUpstreamResilience(GatewayProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties.getUpstream();
        this.meterRegistry = meterRegistry;
        for (GatewayProperties.Route route : properties.getRoutes()) {
            if (route.getId() != null) {
                states.put(route.getId(), new RouteState(this.properties));
            }
        }
    }

    /**
     * Attempts immediate admission to the route's circuit and bulkhead.
     *
     * @param route resolved finite gateway route
     * @return permit that must be closed after the forwarding attempt
     * @throws GatewayUpstreamException when the circuit is open or bulkhead is full
     */
    public Permit acquire(GatewayRoute route) {
        String routeId = route == null ? "unknown" : route.id();
        RouteState state = states.computeIfAbsent(routeId, ignored -> new RouteState(properties));
        CircuitPermit circuitPermit = state.circuit.tryAcquire();
        if (!circuitPermit.admitted()) {
            counter("gateway.upstream.circuit.open", routeId,
                    "Requests rejected because the upstream circuit is open").increment();
            throw unavailable("circuit_open", openDurationSeconds());
        }
        if (!state.bulkhead.tryAcquire()) {
            state.circuit.cancelProbe(circuitPermit.probe());
            counter("gateway.upstream.bulkhead.rejections", routeId,
                    "Requests rejected because the upstream route bulkhead is full").increment();
            throw unavailable("bulkhead_rejected", 1);
        }
        return new Permit(routeId, state, circuitPermit.probe());
    }

    private GatewayUpstreamException unavailable(String failureClass, int retryAfterSeconds) {
        return GatewayUpstreamException.serviceUnavailable(failureClass, retryAfterSeconds);
    }

    private int openDurationSeconds() {
        Duration openDuration = properties.getCircuitBreaker().getOpenDuration();
        return (int) Math.max(1L, (openDuration.toMillis() + 999L) / 1000L);
    }

    private Counter counter(String name, String routeId, String description) {
        return Counter.builder(name)
                .description(description)
                .tag("route", routeTag(routeId))
                .register(meterRegistry);
    }

    private void recordLatency(String routeId, long startNanos) {
        Timer.builder("gateway.upstream.latency")
                .description("Gateway upstream forwarding latency")
                .tag("route", routeTag(routeId))
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private static String routeTag(String routeId) {
        return routeId == null || routeId.isBlank() ? "unknown" : routeId;
    }

    /** A single in-flight route admission that releases its bulkhead permit exactly once. */
    public final class Permit implements AutoCloseable {

        private final String routeId;
        private final RouteState state;
        private final boolean circuitProbe;
        private final long startNanos = System.nanoTime();
        private boolean outcomeRecorded;
        private boolean closed;

        private Permit(String routeId, RouteState state, boolean circuitProbe) {
            this.routeId = routeId;
            this.state = state;
            this.circuitProbe = circuitProbe;
        }

        /** Records an upstream response that should count as a healthy dependency call. */
        public void recordSuccess() {
            if (!outcomeRecorded) {
                outcomeRecorded = true;
                state.circuit.recordSuccess(circuitProbe);
            }
        }

        /** Records a transport, timeout, oversized-response, or 5xx dependency failure. */
        public void recordFailure() {
            if (!outcomeRecorded) {
                outcomeRecorded = true;
                state.circuit.recordFailure(circuitProbe);
                counter("gateway.upstream.failures", routeId,
                        "Upstream failures observed by the gateway").increment();
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                state.bulkhead.release();
                recordLatency(routeId, startNanos);
            }
        }
    }

    private record CircuitPermit(boolean admitted, boolean probe) {
    }

    private static final class RouteState {

        private final Semaphore bulkhead;
        private final Circuit circuit;

        private RouteState(GatewayProperties.Upstream properties) {
            this.bulkhead = new Semaphore(properties.getBulkhead().getMaxConcurrentRequests(), true);
            this.circuit = new Circuit(
                    properties.getCircuitBreaker().getFailureThreshold(),
                    properties.getCircuitBreaker().getOpenDuration());
        }
    }

    private static final class Circuit {

        private final int failureThreshold;
        private final long openDurationNanos;
        private int consecutiveFailures;
        private long openedAtNanos;
        private State state = State.CLOSED;

        private Circuit(int failureThreshold, Duration openDuration) {
            this.failureThreshold = failureThreshold;
            this.openDurationNanos = openDuration.toNanos();
        }

        private synchronized CircuitPermit tryAcquire() {
            long now = System.nanoTime();
            if (state == State.OPEN) {
                if (now - openedAtNanos < openDurationNanos) {
                    return new CircuitPermit(false, false);
                }
                state = State.HALF_OPEN;
                return new CircuitPermit(true, true);
            }
            if (state == State.HALF_OPEN) {
                return new CircuitPermit(false, false);
            }
            return new CircuitPermit(true, false);
        }

        private synchronized void cancelProbe(boolean probe) {
            if (probe && state == State.HALF_OPEN) {
                state = State.OPEN;
                openedAtNanos = System.nanoTime();
            }
        }

        private synchronized void recordSuccess(boolean probe) {
            if (probe || state == State.HALF_OPEN) {
                state = State.CLOSED;
            }
            consecutiveFailures = 0;
        }

        private synchronized void recordFailure(boolean probe) {
            if (probe || state == State.HALF_OPEN || ++consecutiveFailures >= failureThreshold) {
                state = State.OPEN;
                openedAtNanos = System.nanoTime();
                consecutiveFailures = 0;
            }
        }

        private enum State {
            CLOSED,
            OPEN,
            HALF_OPEN
        }
    }
}
