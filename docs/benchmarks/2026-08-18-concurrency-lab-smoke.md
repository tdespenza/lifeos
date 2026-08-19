# Concurrency Lab smoke result — 2026-08-18

This is a bounded local comparison of the five concurrency strategies in the Concurrency Lab.
It is a reproducible harness measurement, not a production throughput or latency claim. The run
used Java 25 preview APIs where required, 16 tasks, a 2 ms bounded task delay, and the fixed
deadlines configured by the lab.

## Reproduction

```bash
JAVA_HOME="$JAVA_HOME" ./gradlew :labs:concurrency-lab:run --args=16
```

## Captured output

```text
{"strategy":"platform-threads","submitted":16,"completed":16,"cancelledOrTimedOut":0,"timedOut":false,"elapsedMillis":8,"boundedAndAccounted":true,"failure":""}
{"strategy":"virtual-threads","submitted":16,"completed":16,"cancelledOrTimedOut":0,"timedOut":false,"elapsedMillis":6,"boundedAndAccounted":true,"failure":""}
{"strategy":"completable-future","submitted":16,"completed":16,"cancelledOrTimedOut":0,"timedOut":false,"elapsedMillis":4,"boundedAndAccounted":true,"failure":""}
{"strategy":"structured-concurrency-preview","submitted":16,"completed":16,"cancelledOrTimedOut":0,"timedOut":false,"elapsedMillis":5,"boundedAndAccounted":true,"failure":""}
{"strategy":"scoped-value","submitted":16,"completed":16,"cancelledOrTimedOut":0,"timedOut":false,"elapsedMillis":1,"boundedAndAccounted":true,"failure":""}
```

The results prove finite task accounting and inherited context behavior for this harness input.
Elapsed values vary with JIT warmup, CPU, and host load; no regression threshold is inferred from
one run. Service-level I/O, cancellation under saturation, and long-running load comparisons still
require a dedicated benchmark environment.
