# Concurrency Lab

This is a runnable lab for comparing platform threads, virtual threads, `ExecutorService`,
`CompletableFuture`, Java 25 structured concurrency, and `ScopedValue` in the same bounded
workload. Each run reports completion, cancellation/timeout accounting, elapsed time, and whether
the correlation binding reached every structured child. It is teaching material, not a production
scheduler.

## Exercises

1. Run a bounded I/O fan-out on platform threads and virtual threads.
2. Express the same fan-out with `ExecutorService` and `CompletableFuture`, cancelling one child.
3. Port it to `StructuredTaskScope` with a deadline and sibling cancellation.
4. Bind correlation/tenant values with `ScopedValue` and prove child propagation and scope cleanup.
5. Capture a bounded thread-dump summary and an opt-in in-memory JFR sample for diagnosis.
6. Compare fixed work budgets under load and document the methodology in `docs/benchmarks/`.

All inputs, task counts, queue sizes, and deadlines are capped. A cancellation or timeout is a
first-class result; no exercise sleeps or allocates without a bound.

Run the demo with Java 25 preview enabled by the Gradle application task:

```bash
./gradlew :labs:concurrency-lab:run --args=16
./gradlew :labs:concurrency-lab:test
```

The demo emits one bounded JSON result for platform threads, virtual threads, completable futures,
structured concurrency, and scoped-value propagation. The module's tests assert task accounting,
finite deadlines, structured-scope execution, inherited context cleanup, and bounded diagnostic
capture. JFR samples remain in memory unless a caller explicitly adds a reviewed dump path.
