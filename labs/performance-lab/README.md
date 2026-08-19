# Performance Lab

This lab contains a deterministic local Java harness plus a bounded k6 readiness contract for REST,
GraphQL aggregation, generated gRPC contracts, virtual threads, GC/JFR, PostgreSQL query plans,
Redis hit ratios, and container limits. The local harness includes map-lookup and bounded hot/cold
cache workloads; each run records workload, JDK, seed/input distribution, duration, concurrency,
and raw artifacts. The lab never invents performance numbers or treats a smoke run as a capacity
claim.

Run the local harness with:

```bash
./gradlew :labs:performance-lab:run --args=10000
./gradlew :labs:performance-lab:test
```

The executable prints one measured map-lookup result and one measured cache result. The cache
fixture reports hits, misses, final bounded size, and hit ratio; it is a local eviction harness,
not a Redis benchmark.

Run the k6 readiness contract only against disposable staging/local services:

```bash
LIFEOS_PERFORMANCE_GATEWAY_BASE_URL=http://localhost:8080 \
  k6 run labs/performance-lab/k6/dashboard-smoke.js
```

Stop on error-rate, latency, heap, or queue limits and keep payloads synthetic and bounded.
