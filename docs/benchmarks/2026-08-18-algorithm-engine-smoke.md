# Algorithm Engine Smoke Benchmark — 2026-08-18 UTC

This is a measured, correctness-checked smoke benchmark for the shared Java algorithm library. It
is not a JMH baseline and must not be used to claim production throughput or compare JVM tuning.
Its purpose is to prove the benchmark harness, bounded fixtures, and metadata capture work before
future performance-sensitive services rely on them.

## Reproduction

```text
./gradlew :contracts:algorithm-engine:runAlgorithmBenchmarks
```

The task warms each fixture eight times, records twenty `System.nanoTime` samples, verifies each
algorithm’s expected result size, and writes a JSON report under
`contracts/algorithm-engine/build/reports/benchmarks/algorithm-engine.json`.

## Measured run

The following run completed successfully on 2026-08-18T02:09:27Z using Java
`25.0.3+9-LTS` on macOS/aarch64 with 16 available processors. Values are wall-clock nanoseconds
and are deliberately recorded as host-specific observations, not targets.

| Fixture | Input / expected result | Median | P95 |
| --- | --- | ---: | ---: |
| Bounded topological order | 1,000 nodes / 1,000 ordered nodes | 214,542 ns | 707,167 ns |
| Bounded interval conflicts | 1,000 intervals / 999 conflicts | 457,125 ns | 504,500 ns |
| Bounded priority ranking | 5,000 candidates / 100 results | 454,042 ns | 727,042 ns |

## Limits and follow-up

- The harness is intentionally dependency-free, so it does not replace JMH forks, profilers, or
  CPU pinning.
- Garbage collection, CPU frequency, other processes, and a single local run affect these values.
- No regression threshold is configured from this one observation.
- A JMH suite and service-level load tests are still required before a latency/throughput SLO or
  architecture claim is made.
