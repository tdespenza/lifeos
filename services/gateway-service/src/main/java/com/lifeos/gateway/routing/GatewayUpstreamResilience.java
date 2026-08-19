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
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-route upstream bulkheads and consecutive-failure circuit breakers.
 *
 * <p>For a finite set of configured route IDs, the state machine keeps one semaphore and one
 * circuit state per route. Admission never waits for a permit: a request either acquires the
 * route's bounded bulkhead immediately or receives a controlled degraded response, so slow
 * upstreams cannot create an unbounded queue in the gateway. Circuit state is local operational
 * state: it protects one gateway instance and is deliberately not treated as authorization or
 * business state.
 *
 * <p>Under the configured-route invariant, an admission attempt is O(1) average time for the
 * concurrent route-map lookup, semaphore operation, and synchronized circuit transition. Memory
 * is O(R) for {@code R} configured routes, plus O(1) state per in-flight permit; there is no
 * unbounded wait queue. The worst case is immediate rejection when the circuit is open, a
 * half-open probe is already active, or the route bulkhead is full. Callers must pass only
 * startup-validated route IDs; passing arbitrary unbounded route IDs would grow the map and
 * violate the O(R) bound.
 *
 * <p>A distributed circuit state or a resilience library could coordinate decisions across gateway
 * instances, but would add network failure modes and shared state to this local protection layer.
 * The local route-keyed design is preferred because it gives bounded, instance-local isolation
 * with deterministic no-wait admission; cross-instance consistency remains the rate limiter's
 * responsibility.
 */
@Component
public class GatewayUpstreamResilience {

    private final GatewayProperties.Upstream properties;
    private final MeterRegistry meterRegistry;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<String, RouteState> states = new ConcurrentHashMap<>();

    @Autowired
    public GatewayUpstreamResilience(GatewayProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry, System::nanoTime);
    }

    GatewayUpstreamResilience(
            GatewayProperties properties, MeterRegistry meterRegistry, LongSupplier nanoTime) {
        this.properties = properties.getUpstream();
        this.meterRegistry = meterRegistry;
        this.nanoTime = nanoTime;
        for (GatewayProperties.Route route : properties.getRoutes()) {
            if (route.getId() != null) {
                states.put(route.getId(), new RouteState(this.properties, nanoTime, meterRegistry, route.getId()));
                if (route.isMediaUploadStreaming()) {
                    String mediaUploadId = route.getId() + "-media-upload";
                    states.put(mediaUploadId, new RouteState(this.properties, nanoTime, meterRegistry, mediaUploadId));
                }
                if (route.isMediaHlsStreaming()) {
                    String mediaHlsId = route.getId() + "-media-hls";
                    states.put(mediaHlsId, new RouteState(this.properties, nanoTime, meterRegistry, mediaHlsId));
                }
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
        return acquireRouteId(routeId);
    }

    /**
     * Acquires the finite virtual route state reserved for exact Media source uploads.
     *
     * <p>A long source upload must not consume the ordinary Media metadata/session bulkhead or
     * circuit state. The virtual key is materialized only from a startup-validated Media route,
     * so it retains the finite route-state invariant.
     *
     * @param route configured Media assets route
     * @return permit that must be closed after the forwarding attempt
     */
    public Permit acquireMediaUpload(GatewayRoute route) {
        if (route == null || !route.mediaUploadStreaming()) {
            throw new IllegalArgumentException("Media upload resilience requires a configured Media upload route");
        }
        return acquireRouteId(route.mediaUploadResilienceId());
    }

    /**
     * Acquires the finite virtual route state reserved for exact Media HLS reads.
     *
     * <p>Long HLS reads cannot starve ordinary Media metadata/session traffic or source uploads.
     * The virtual key is materialized only from a startup-validated Media route, preserving
     * bounded route state and low-cardinality metrics.
     *
     * @param route configured Media assets route
     * @return permit that must be closed after the forwarding attempt
     */
    public Permit acquireMediaHls(GatewayRoute route) {
        if (route == null || !route.mediaHlsStreaming()) {
            throw new IllegalArgumentException("Media HLS resilience requires a configured Media HLS route");
        }
        return acquireRouteId(route.mediaHlsResilienceId());
    }

    private Permit acquireRouteId(String routeId) {
        RouteState state = states.computeIfAbsent(
                routeId, ignored -> new RouteState(properties, nanoTime, meterRegistry, routeId));
        CircuitPermit circuitPermit = state.circuit.tryAcquire();
        if (!circuitPermit.admitted()) {
            state.circuitOpenRejections.increment();
            throw unavailable("circuit_open", openDurationSeconds());
        }
        if (!state.bulkhead.tryAcquire()) {
            state.circuit.cancelProbe(circuitPermit.probe(), circuitPermit.probeGeneration());
            state.bulkheadRejections.increment();
            throw unavailable("bulkhead_rejected", 1);
        }
        return new Permit(state, circuitPermit.probe(), circuitPermit.probeGeneration());
    }

    private GatewayUpstreamException unavailable(String failureClass, int retryAfterSeconds) {
        return GatewayUpstreamException.serviceUnavailable(failureClass, retryAfterSeconds);
    }

    private int openDurationSeconds() {
        Duration openDuration = properties.getCircuitBreaker().getOpenDuration();
        return (int) Math.max(1L, (openDuration.toMillis() + 999L) / 1000L);
    }

    private static String routeTag(String routeId) {
        return routeId == null || routeId.isBlank() ? "unknown" : routeId;
    }

    /** A single in-flight route admission that releases its bulkhead permit exactly once. */
    public final class Permit implements AutoCloseable {

        private final RouteState state;
        private final boolean circuitProbe;
        private final long probeGeneration;
        private final long startNanos;
        private boolean outcomeRecorded;
        private boolean closed;

        private Permit(RouteState state, boolean circuitProbe, long probeGeneration) {
            this.state = state;
            this.circuitProbe = circuitProbe;
            this.probeGeneration = probeGeneration;
            this.startNanos = nanoTime.getAsLong();
        }

        /** Records an upstream response that should count as a healthy dependency call. */
        public void recordSuccess() {
            if (!outcomeRecorded) {
                outcomeRecorded = true;
                state.circuit.recordSuccess(circuitProbe, probeGeneration);
            }
        }

        /** Records a transport, timeout, oversized-response, or 5xx dependency failure. */
        public void recordFailure() {
            if (!outcomeRecorded) {
                outcomeRecorded = true;
                state.circuit.recordFailure(circuitProbe, probeGeneration);
                state.failures.increment();
            }
        }

        /**
         * Releases this admission without treating a client-side validation abort as an upstream
         * health signal.
         *
         * <p>A request-streaming relay can discover a dishonest or absent content length only
         * after it has opened an upstream connection. Such an oversized client body must not open
         * a dependency circuit. If the admission was a half-open probe, it is cancelled so the
         * next real upstream request remains the sole health probe.
         */
        public void recordAbandoned() {
            if (!outcomeRecorded) {
                outcomeRecorded = true;
                state.circuit.cancelProbe(circuitProbe, probeGeneration);
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (!outcomeRecorded) {
                    recordFailure();
                }
                state.bulkhead.release();
                state.latency.record(nanoTime.getAsLong() - startNanos, TimeUnit.NANOSECONDS);
            }
        }
    }

    private record CircuitPermit(boolean admitted, boolean probe, long probeGeneration) {
    }

    private static final class RouteState {

        private final Semaphore bulkhead;
        private final Circuit circuit;
        private final Counter circuitOpenRejections;
        private final Counter bulkheadRejections;
        private final Counter failures;
        private final Timer latency;

        private RouteState(
                GatewayProperties.Upstream properties,
                LongSupplier nanoTime,
                MeterRegistry meterRegistry,
                String routeId) {
            this.bulkhead = new Semaphore(properties.getBulkhead().getMaxConcurrentRequests(), true);
            this.circuit = new Circuit(
                    properties.getCircuitBreaker().getFailureThreshold(),
                    properties.getCircuitBreaker().getOpenDuration(),
                    nanoTime);
            String route = routeTag(routeId);
            this.circuitOpenRejections = Counter.builder("gateway.upstream.circuit.open")
                    .description("Requests rejected because the upstream circuit is open")
                    .tag("route", route)
                    .register(meterRegistry);
            this.bulkheadRejections = Counter.builder("gateway.upstream.bulkhead.rejections")
                    .description("Requests rejected because the upstream route bulkhead is full")
                    .tag("route", route)
                    .register(meterRegistry);
            this.failures = Counter.builder("gateway.upstream.failures")
                    .description("Upstream failures observed by the gateway")
                    .tag("route", route)
                    .register(meterRegistry);
            this.latency = Timer.builder("gateway.upstream.latency")
                    .description("Gateway upstream forwarding latency")
                    .tag("route", route)
                    .publishPercentileHistogram()
                    .register(meterRegistry);
        }
    }

    private static final class Circuit {

        private final int failureThreshold;
        private final long openDurationNanos;
        private final LongSupplier nanoTime;
        private int consecutiveFailures;
        private long openedAtNanos;
        private long halfOpenAtNanos;
        private long probeGeneration;
        private State state = State.CLOSED;

        private Circuit(int failureThreshold, Duration openDuration, LongSupplier nanoTime) {
            this.failureThreshold = failureThreshold;
            this.openDurationNanos = openDuration.toNanos();
            this.nanoTime = nanoTime;
        }

        private synchronized CircuitPermit tryAcquire() {
            long now = nanoTime.getAsLong();
            if (state == State.HALF_OPEN
                    && elapsedNanos(now, halfOpenAtNanos) >= openDurationNanos) {
                state = State.OPEN;
                openedAtNanos = now - openDurationNanos;
            }
            if (state == State.OPEN) {
                if (elapsedNanos(now, openedAtNanos) < openDurationNanos) {
                    return new CircuitPermit(false, false, 0);
                }
                state = State.HALF_OPEN;
                halfOpenAtNanos = now;
                probeGeneration++;
                return new CircuitPermit(true, true, probeGeneration);
            }
            if (state == State.HALF_OPEN) {
                return new CircuitPermit(false, false, 0);
            }
            return new CircuitPermit(true, false, 0);
        }

        private synchronized void cancelProbe(boolean probe, long generation) {
            if (probe && state == State.HALF_OPEN && generation == probeGeneration) {
                // Local saturation is not an upstream failure, so retain the existing cool-down.
                state = State.OPEN;
                probeGeneration++;
            }
        }

        private synchronized void recordSuccess(boolean probe, long generation) {
            if (probe) {
                if (state == State.HALF_OPEN && generation == probeGeneration) {
                    state = State.CLOSED;
                    consecutiveFailures = 0;
                }
            } else if (state == State.CLOSED) {
                consecutiveFailures = 0;
            }
        }

        private synchronized void recordFailure(boolean probe, long generation) {
            if (probe) {
                if (state == State.HALF_OPEN && generation == probeGeneration) {
                    open(nanoTime.getAsLong());
                }
            } else if (state == State.CLOSED && ++consecutiveFailures >= failureThreshold) {
                open(nanoTime.getAsLong());
            }
        }

        private void open(long now) {
            state = State.OPEN;
            openedAtNanos = now;
            consecutiveFailures = 0;
        }

        private static long elapsedNanos(long now, long start) {
            return now - start;
        }

        private enum State {
            CLOSED,
            OPEN,
            HALF_OPEN
        }
    }
}
