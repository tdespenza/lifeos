# Distributed Systems Lab

Bounded executable exercises demonstrate capped retries, circuit breakers, backpressure,
idempotency, outbox relay, service discovery, W3C trace-context propagation, saga compensation,
CQRS/event sourcing, lease-based distributed locks, leader election, and consistent-hash sharding.
Each exercise uses a deterministic local fake dependency and explicitly states that it is not a
deployment.

Every scenario documents the invariant, failure injection, recovery proof, queue/concurrency cap,
correlation propagation, and dead-letter behavior. The production services remain the source of
truth for authorization, persistence, and secret handling.

Run the executable slice with:

```bash
./gradlew :labs:distributed-systems-lab:run
./gradlew :labs:distributed-systems-lab:test
```

The tests prove bounded retry, circuit open/half-open recovery, queue rejection under backpressure,
idempotency dedupe, outbox delivery dedupe, W3C parent/child propagation with span backpressure,
reverse-order compensation, immutable event replay, lease ownership, election handoff, shard
routing, and command/read-model separation.
