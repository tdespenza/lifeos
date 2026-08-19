package com.lifeos.labs.distributed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.labs.distributed.DistributedSystemsLab.BoundedBackpressureQueue;
import com.lifeos.labs.distributed.DistributedSystemsLab.CircuitBreaker;
import com.lifeos.labs.distributed.DistributedSystemsLab.CircuitOpenException;
import com.lifeos.labs.distributed.DistributedSystemsLab.CircuitState;
import com.lifeos.labs.distributed.DistributedSystemsLab.ConsistentHashRouter;
import com.lifeos.labs.distributed.DistributedSystemsLab.CqrsProjection;
import com.lifeos.labs.distributed.DistributedSystemsLab.EventSourcedAggregate;
import com.lifeos.labs.distributed.DistributedSystemsLab.IdempotencyLedger;
import com.lifeos.labs.distributed.DistributedSystemsLab.LeaderElector;
import com.lifeos.labs.distributed.DistributedSystemsLab.LeaseLock;
import com.lifeos.labs.distributed.DistributedSystemsLab.OutboxRelay;
import com.lifeos.labs.distributed.DistributedSystemsLab.RetryPolicy;
import com.lifeos.labs.distributed.DistributedSystemsLab.SagaStep;
import com.lifeos.labs.distributed.DistributedSystemsLab.ServiceRegistry;
import com.lifeos.labs.distributed.DistributedSystemsLab.TraceCollector;
import com.lifeos.labs.distributed.DistributedSystemsLab.TraceContext;
import com.lifeos.labs.distributed.DistributedSystemsLab.TracePropagation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DistributedSystemsLabTest {

    @Test
    void retriesAreBoundedAndEventuallySucceed() {
        var result = RetryPolicy.execute(3, Duration.ofMillis(1), new FailingThenSuccessForTest(2));

        assertTrue(result.succeeded());
        assertEquals(3, result.attempts());
    }

    @Test
    void circuitOpensAfterThresholdAndRecoversWithProbe() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofMillis(25));
        assertThrows(RuntimeException.class, () -> breaker.execute(() -> { throw new IllegalStateException(); }));
        assertThrows(RuntimeException.class, () -> breaker.execute(() -> { throw new IllegalStateException(); }));
        assertEquals(CircuitState.OPEN, breaker.state());
        assertThrows(CircuitOpenException.class, () -> breaker.execute(() -> "blocked"));
        Thread.sleep(35);
        assertEquals("recovered", breaker.execute(() -> "recovered"));
        assertEquals(CircuitState.CLOSED, breaker.state());
    }

    @Test
    void backpressureRejectsBeyondCapacity() {
        BoundedBackpressureQueue<String> queue = new BoundedBackpressureQueue<>(2);

        assertTrue(queue.offer("a"));
        assertTrue(queue.offer("b"));
        assertTrue(!queue.offer("c"));
        assertEquals("a", queue.poll());
        assertTrue(queue.offer("c"));
    }

    @Test
    void idempotencyAndOutboxRelayDeduplicateEvents() {
        IdempotencyLedger ledger = new IdempotencyLedger();
        assertTrue(ledger.commit("event-1"));
        assertTrue(!ledger.commit("event-1"));

        OutboxRelay relay = new OutboxRelay(2);
        assertTrue(relay.enqueue("event-1"));
        assertTrue(relay.enqueue("event-2"));
        assertTrue(!relay.enqueue("event-3"));
        assertTrue(relay.relayOne());
        assertTrue(relay.relayOne());
        assertEquals(2, relay.deliveredCount());
    }

    @Test
    void discoverySagaAndEventSourcingAreBounded() {
        ServiceRegistry registry = new ServiceRegistry(2);
        registry.register("task-goal", "http://loopback.test:8082");
        assertEquals("http://loopback.test:8082", registry.resolve("task-goal"));

        List<String> effects = new ArrayList<>();
        var saga = DistributedSystemsLab.executeSaga(List.of(
                new SagaStep("reserve", () -> effects.add("reserved"), () -> effects.add("released")),
                new SagaStep("publish", () -> { throw new IllegalStateException("broker"); }, () -> effects.add("unpublished"))));
        assertTrue(!saga.succeeded());
        assertEquals(List.of("reserve"), saga.compensated());
        assertEquals(List.of("reserved", "released"), effects);

        EventSourcedAggregate aggregate = new EventSourcedAggregate(4);
        aggregate.append("CREATED", "one");
        aggregate.append("RENAMED", "two");
        assertEquals("one,two", aggregate.replay("", (state, event) -> state.isEmpty()
                ? event.payload() : state + "," + event.payload()));
    }

    @Test
    void traceContextPropagatesParentAndHonorsCollectorCapacity() {
        TraceCollector collector = new TraceCollector(2);
        TraceContext root = collector.root(
                "0123456789abcdef0123456789abcdef", "gateway.request").context();
        TraceContext child = collector.child(root, "identity.validate").context();

        assertEquals(root.traceId(), child.traceId());
        assertEquals(root.spanId(), child.parentSpanId());
        TraceContext extracted = TracePropagation.extract(TracePropagation.inject(child));
        assertEquals(child.traceId(), extracted.traceId());
        assertEquals(child.spanId(), extracted.spanId());
        assertNull(extracted.parentSpanId());
        assertEquals(2, collector.spans().size());
        assertThrows(IllegalStateException.class, () -> collector.child(child, "task.forward"));
    }

    @Test
    void leasesElectionShardingAndCqrsPreserveTheirInvariants() throws InterruptedException {
        LeaseLock lock = new LeaseLock();
        assertTrue(lock.acquire("worker-a", Duration.ofSeconds(1)));
        assertTrue(!lock.acquire("worker-b", Duration.ofSeconds(1)));
        assertTrue(lock.release("worker-a"));

        LeaderElector elector = new LeaderElector();
        assertTrue(elector.tryBecomeLeader("worker-a", Duration.ofSeconds(1)));
        assertEquals("worker-a", elector.leader());
        assertTrue(elector.resign("worker-a"));

        ConsistentHashRouter router = new ConsistentHashRouter();
        router.addNode("node-a");
        router.addNode("node-b");
        assertTrue(List.of("node-a", "node-b").contains(router.route("account-1")));

        CqrsProjection projection = new CqrsProjection();
        assertTrue(projection.applyCommand("command-1", "status", "ACTIVE"));
        assertTrue(!projection.applyCommand("command-1", "status", "MUTATED"));
        assertEquals("ACTIVE", projection.query("status"));
    }

    private static final class FailingThenSuccessForTest implements java.util.function.Supplier<String> {
        private int failuresRemaining;

        private FailingThenSuccessForTest(int failuresRemaining) {
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
