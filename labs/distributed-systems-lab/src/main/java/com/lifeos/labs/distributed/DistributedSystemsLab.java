package com.lifeos.labs.distributed;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Small executable demonstrations of bounded distributed-systems controls.
 *
 * <p>The lab deliberately keeps state in memory and caps every queue/retry count. It demonstrates
 * the control-flow invariants without pretending to be a broker, database, or production lock.
 */
public final class DistributedSystemsLab {

    private DistributedSystemsLab() {}

    public static void main(String[] args) {
        RetryResult<String> retry = RetryPolicy.execute(3, Duration.ofMillis(5), new FailingThenSuccess(2));
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(1));
        breaker.execute(() -> "first");
        breaker.execute(() -> "second");
        System.out.println("{\"retryAttempts\":" + retry.attempts() + ",\"retrySucceeded\":"
                + retry.succeeded() + ",\"circuitState\":\"" + breaker.state() + "\"}");
    }

    public record RetryResult<T>(T value, int attempts, boolean succeeded, Duration elapsed) {}

    public static final class RetryPolicy {

        private RetryPolicy() {}

        public static <T> RetryResult<T> execute(int maxAttempts, Duration initialBackoff, Supplier<T> operation) {
            if (maxAttempts < 1 || maxAttempts > 5 || initialBackoff.isNegative() || initialBackoff.isZero()) {
                throw new IllegalArgumentException("retry bounds are invalid");
            }
            Instant started = Instant.now();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return new RetryResult<>(operation.get(), attempt, true, elapsed(started));
                } catch (RuntimeException failure) {
                    if (attempt == maxAttempts) {
                        break;
                    }
                    long delayMillis = Math.min(250, initialBackoff.toMillis() << (attempt - 1));
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return new RetryResult<>(null, attempt, false, elapsed(started));
                    }
                }
            }
            return new RetryResult<>(null, maxAttempts, false, elapsed(started));
        }
    }

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public static final class CircuitBreaker {

        private final int failureThreshold;
        private final Duration coolDown;
        private int failures;
        private Instant openedAt;
        private CircuitState state = CircuitState.CLOSED;

        public CircuitBreaker(int failureThreshold, Duration coolDown) {
            if (failureThreshold < 1 || failureThreshold > 10 || coolDown.isNegative() || coolDown.isZero()) {
                throw new IllegalArgumentException("circuit bounds are invalid");
            }
            this.failureThreshold = failureThreshold;
            this.coolDown = coolDown;
        }

        public synchronized <T> T execute(Supplier<T> operation) {
            if (state == CircuitState.OPEN) {
                if (openedAt.plus(coolDown).isAfter(Instant.now())) {
                    throw new CircuitOpenException();
                }
                state = CircuitState.HALF_OPEN;
            }
            try {
                T result = operation.get();
                failures = 0;
                state = CircuitState.CLOSED;
                return result;
            } catch (RuntimeException failure) {
                failures++;
                if (failures >= failureThreshold) {
                    state = CircuitState.OPEN;
                    openedAt = Instant.now();
                }
                throw failure;
            }
        }

        public synchronized CircuitState state() {
            return state;
        }
    }

    public static final class BoundedBackpressureQueue<T> {

        private final Queue<T> queue = new ArrayDeque<>();
        private final int capacity;

        public BoundedBackpressureQueue(int capacity) {
            if (capacity < 1 || capacity > 1_024) {
                throw new IllegalArgumentException("queue capacity must be between 1 and 1024");
            }
            this.capacity = capacity;
        }

        public synchronized boolean offer(T value) {
            if (value == null || queue.size() >= capacity) {
                return false;
            }
            return queue.offer(value);
        }

        public synchronized T poll() {
            return queue.poll();
        }

        public synchronized int size() {
            return queue.size();
        }
    }

    public static final class IdempotencyLedger {

        private final Set<String> committed = new HashSet<>();

        public synchronized boolean commit(String key) {
            if (key == null || key.isBlank() || key.length() > 128) {
                throw new IllegalArgumentException("idempotency key is invalid");
            }
            return committed.add(key);
        }

        public synchronized int size() {
            return committed.size();
        }
    }

    public static final class OutboxRelay {

        private final BoundedBackpressureQueue<String> pending;
        private final IdempotencyLedger delivered = new IdempotencyLedger();

        public OutboxRelay(int capacity) {
            pending = new BoundedBackpressureQueue<>(capacity);
        }

        public boolean enqueue(String eventId) {
            return pending.offer(eventId);
        }

        public boolean relayOne() {
            String eventId = pending.poll();
            return eventId != null && delivered.commit(eventId);
        }

        public int deliveredCount() {
            return delivered.size();
        }
    }

    /** Bounded service-discovery registry; registrations are explicit and never resolve arbitrary hosts. */
    public static final class ServiceRegistry {
        private final Map<String, String> endpoints = new LinkedHashMap<>();
        private final int capacity;

        public ServiceRegistry(int capacity) {
            if (capacity < 1 || capacity > 64) {
                throw new IllegalArgumentException("registry capacity must be between 1 and 64");
            }
            this.capacity = capacity;
        }

        public synchronized void register(String service, String endpoint) {
            if (service == null || !service.matches("[a-z][a-z0-9-]{0,63}")
                    || endpoint == null || endpoint.isBlank() || endpoint.length() > 256) {
                throw new IllegalArgumentException("service registration is invalid");
            }
            if (!endpoints.containsKey(service) && endpoints.size() >= capacity) {
                throw new IllegalStateException("service registry capacity exceeded");
            }
            endpoints.put(service, endpoint);
        }

        public synchronized String resolve(String service) {
            return endpoints.get(service);
        }

        public synchronized int size() {
            return endpoints.size();
        }
    }

    /**
     * Minimal W3C trace-context fixture. IDs are opaque, fixed-width values and never contain
     * caller data; the propagation map contains only the standard {@code traceparent} field.
     */
    public record TraceContext(String traceId, String spanId, String parentSpanId) {

        public TraceContext {
            requireHex(traceId, 32, "traceId");
            requireHex(spanId, 16, "spanId");
            if (parentSpanId != null) {
                requireHex(parentSpanId, 16, "parentSpanId");
            }
            if (traceId.chars().allMatch(character -> character == '0')
                    || spanId.chars().allMatch(character -> character == '0')) {
                throw new IllegalArgumentException("trace IDs must not be all zeroes");
            }
        }

        public String traceparent() {
            return "00-" + traceId + "-" + spanId + "-01";
        }

        private static void requireHex(String value, int length, String name) {
            if (value == null || !value.matches("[0-9a-f]{" + length + "}")) {
                throw new IllegalArgumentException(name + " must be lowercase hexadecimal");
            }
        }
    }

    public record TraceSpan(String name, TraceContext context) {
        public TraceSpan {
            if (name == null || !name.matches("[a-z][a-z0-9._/-]{0,63}")) {
                throw new IllegalArgumentException("span name is invalid");
            }
        }
    }

    /** Bounded in-memory span collector used to demonstrate parent/child propagation and backpressure. */
    public static final class TraceCollector {

        private final int capacity;
        private final List<TraceSpan> spans = new ArrayList<>();
        private long nextSpanNumber = 1L;

        public TraceCollector(int capacity) {
            if (capacity < 1 || capacity > 256) {
                throw new IllegalArgumentException("trace capacity must be between 1 and 256");
            }
            this.capacity = capacity;
        }

        public synchronized TraceSpan root(String traceId, String name) {
            return add(new TraceSpan(name, new TraceContext(traceId, nextSpanId(), null)));
        }

        public synchronized TraceSpan child(TraceContext parent, String name) {
            if (parent == null) {
                throw new IllegalArgumentException("parent trace context is required");
            }
            return add(new TraceSpan(
                    name,
                    new TraceContext(parent.traceId(), nextSpanId(), parent.spanId())));
        }

        public synchronized List<TraceSpan> spans() {
            return List.copyOf(spans);
        }

        private TraceSpan add(TraceSpan span) {
            if (spans.size() >= capacity) {
                throw new IllegalStateException("trace collector capacity exceeded");
            }
            spans.add(span);
            return span;
        }

        private String nextSpanId() {
            if (nextSpanNumber == Long.MAX_VALUE) {
                throw new IllegalStateException("trace span ID space exhausted");
            }
            return "%016x".formatted(nextSpanNumber++);
        }
    }

    public static final class TracePropagation {

        private TracePropagation() {}

        public static Map<String, String> inject(TraceContext context) {
            if (context == null) {
                throw new IllegalArgumentException("trace context is required");
            }
            return Map.of("traceparent", context.traceparent());
        }

        public static TraceContext extract(Map<String, String> headers) {
            if (headers == null || headers.size() > 16) {
                throw new IllegalArgumentException("trace headers are invalid or unbounded");
            }
            String traceparent = headers.get("traceparent");
            if (traceparent == null) {
                throw new IllegalArgumentException("traceparent is required");
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("00-([0-9a-f]{32})-([0-9a-f]{16})-01")
                    .matcher(traceparent);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("traceparent is invalid");
            }
            return new TraceContext(matcher.group(1), matcher.group(2), null);
        }
    }

    public record SagaStep(String id, Supplier<?> action, Runnable compensation) {
        public SagaStep {
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}") || action == null || compensation == null) {
                throw new IllegalArgumentException("saga step is invalid");
            }
        }
    }

    public record SagaResult(boolean succeeded, List<String> completed, List<String> compensated, String failure) {
        public SagaResult {
            completed = List.copyOf(completed);
            compensated = List.copyOf(compensated);
            if (completed.size() > 16 || compensated.size() > 16 || failure == null || failure.length() > 128) {
                throw new IllegalArgumentException("saga result is invalid or unbounded");
            }
        }
    }

    /** Executes at most sixteen steps and compensates completed steps in reverse order on failure. */
    public static SagaResult executeSaga(List<SagaStep> steps) {
        if (steps == null || steps.isEmpty() || steps.size() > 16) {
            throw new IllegalArgumentException("saga must contain between 1 and 16 steps");
        }
        List<SagaStep> completed = new ArrayList<>();
        try {
            for (SagaStep step : steps) {
                step.action().get();
                completed.add(step);
            }
            return new SagaResult(true, completed.stream().map(SagaStep::id).toList(), List.of(), "");
        } catch (RuntimeException failure) {
            List<String> compensated = new ArrayList<>();
            List<SagaStep> reverse = new ArrayList<>(completed);
            Collections.reverse(reverse);
            for (SagaStep step : reverse) {
                try {
                    step.compensation().run();
                    compensated.add(step.id());
                } catch (RuntimeException ignored) {
                    // A failed compensation is reported by omission; callers must retry/reconcile it.
                }
            }
            return new SagaResult(false, completed.stream().map(SagaStep::id).toList(), compensated,
                    failure.getClass().getSimpleName());
        }
    }

    public record StoredEvent(long sequence, String type, String payload) {
        public StoredEvent {
            if (sequence < 1 || type == null || !type.matches("[A-Z][A-Z0-9_]{0,63}")
                    || payload == null || payload.length() > 4_096) {
                throw new IllegalArgumentException("event is invalid or unbounded");
            }
        }
    }

    /** Append-only event log with deterministic replay; no updates or deletes are allowed. */
    public static final class EventSourcedAggregate {
        private final List<StoredEvent> events = new ArrayList<>();
        private final int capacity;

        public EventSourcedAggregate(int capacity) {
            if (capacity < 1 || capacity > 256) {
                throw new IllegalArgumentException("event capacity must be between 1 and 256");
            }
            this.capacity = capacity;
        }

        public synchronized StoredEvent append(String type, String payload) {
            if (events.size() >= capacity) {
                throw new IllegalStateException("event log capacity exceeded");
            }
            StoredEvent event = new StoredEvent(events.size() + 1L, type, payload);
            events.add(event);
            return event;
        }

        public synchronized List<StoredEvent> events() {
            return List.copyOf(events);
        }

        public synchronized <S> S replay(S initial, BiFunction<S, StoredEvent, S> reducer) {
            if (reducer == null) {
                throw new IllegalArgumentException("reducer must not be null");
            }
            S state = initial;
            for (StoredEvent event : events) {
                state = reducer.apply(state, event);
            }
            return state;
        }
    }

    /** Lease-based distributed-lock fixture; ownership and expiry are explicit. */
    public static final class LeaseLock {
        private String owner;
        private Instant expiresAt = Instant.MIN;

        public synchronized boolean acquire(String candidate, Duration lease) {
            validateLease(candidate, lease);
            Instant now = Instant.now();
            if (owner != null && expiresAt.isAfter(now) && !owner.equals(candidate)) {
                return false;
            }
            owner = candidate;
            expiresAt = now.plus(lease);
            return true;
        }

        public synchronized boolean release(String candidate) {
            if (!candidate.equals(owner)) {
                return false;
            }
            owner = null;
            expiresAt = Instant.MIN;
            return true;
        }

        public synchronized String owner() {
            return owner;
        }
    }

    /** Leader-election fixture built on the same bounded lease invariant. */
    public static final class LeaderElector {
        private final LeaseLock lease = new LeaseLock();

        public boolean tryBecomeLeader(String candidate, Duration leaseDuration) {
            return lease.acquire(candidate, leaseDuration);
        }

        public boolean resign(String candidate) {
            return lease.release(candidate);
        }

        public String leader() {
            return lease.owner();
        }
    }

    /** Deterministic consistent-hash ring with at most sixteen virtual-free nodes. */
    public static final class ConsistentHashRouter {
        private final NavigableMap<Integer, String> ring = new TreeMap<>();

        public synchronized void addNode(String node) {
            if (node == null || !node.matches("[a-z][a-z0-9-]{0,31}") || ring.size() >= 16) {
                throw new IllegalArgumentException("node is invalid or ring is full");
            }
            int hash = stableHash(node);
            while (ring.containsKey(hash)) {
                hash = hash == Integer.MAX_VALUE ? Integer.MIN_VALUE : hash + 1;
            }
            ring.put(hash, node);
        }

        public synchronized String route(String key) {
            if (key == null || key.isBlank() || key.length() > 256 || ring.isEmpty()) {
                throw new IllegalArgumentException("routing key is invalid or ring is empty");
            }
            Map.Entry<Integer, String> entry = ring.ceilingEntry(stableHash(key));
            return (entry == null ? ring.firstEntry() : entry).getValue();
        }

        public synchronized int nodeCount() {
            return ring.size();
        }

        private static int stableHash(String value) {
            return value.hashCode() * 31 + 17;
        }
    }

    /** Tiny CQRS fixture: bounded command dedupe plus an independently rebuilt read projection. */
    public static final class CqrsProjection {
        private final IdempotencyLedger commands = new IdempotencyLedger();
        private final Map<String, String> readModel = new LinkedHashMap<>();

        public synchronized boolean applyCommand(String commandId, String key, String value) {
            if (commandId == null || commandId.isBlank() || key == null || key.isBlank()
                    || key.length() > 64 || value == null || value.length() > 512) {
                throw new IllegalArgumentException("command is invalid or unbounded");
            }
            if (!commands.commit(commandId)) {
                return false;
            }
            readModel.put(key, value);
            return true;
        }

        public synchronized String query(String key) {
            return readModel.get(key);
        }

        public synchronized Map<String, String> snapshot() {
            return Map.copyOf(readModel);
        }
    }

    private static void validateLease(String owner, Duration lease) {
        if (owner == null || owner.isBlank() || owner.length() > 64 || lease == null
                || lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("lease is invalid or unbounded");
        }
    }

    public static final class CircuitOpenException extends RuntimeException {
        public CircuitOpenException() {
            super("circuit is open");
        }
    }

    private static Duration elapsed(Instant started) {
        return Duration.between(started, Instant.now());
    }

    private static final class FailingThenSuccess implements Supplier<String> {
        private int failuresRemaining;

        private FailingThenSuccess(int failuresRemaining) {
            this.failuresRemaining = failuresRemaining;
        }

        @Override
        public String get() {
            if (failuresRemaining-- > 0) {
                throw new IllegalStateException("transient failure");
            }
            return "ok";
        }
    }
}
