# Performance Lab smoke result — 2026-08-19

This is a local correctness/measurement smoke run of the bounded Performance Lab, not a
production capacity claim. It used the repository's Java 25 toolchain on macOS/aarch64 with the
fixed 10,000-operation workload.

## Reproduction

```bash
JAVA_HOME="$JAVA_HOME" ./gradlew :labs:performance-lab:run --args='10000'
```

The run emitted:

```json
{"workload":"bounded-map-lookup","operations":10000,"checksum":636706365,"p50Nanos":42,"p95Nanos":166,"p99Nanos":250,"operationsPerSecond":7304601,"javaRuntime":"25.0.3+9-LTS","methodology":"seed=83877595270995,keySpace=4096"}
{"workload":"bounded-cache-lookup","operations":10000,"capacity":256,"hits":8022,"misses":1978,"finalSize":256,"hitRatio":0.8022,"operationsPerSecond":4535147,"javaRuntime":"25.0.3+9-LTS","methodology":"seed=16527118895647,hotKeySpace=64,coldEvery=5"}
```

The checksum and workload shape are deterministic; latency and operations/second vary with CPU,
JIT warmup, and host load. The cache result is a bounded local eviction fixture, not Redis
latency or production capacity.

## PostgreSQL query-plan probe

The performance lab now includes an opt-in read-only probe for the indexed identity email lookup.
It requires explicit credentials and applies a ten-second statement timeout:

```bash
LIFEOS_BENCHMARK_JDBC_URL=jdbc:postgresql://localhost:5432/lifeos_identity \
LIFEOS_BENCHMARK_JDBC_USERNAME="$LIFEOS_POSTGRES_USER" \
LIFEOS_BENCHMARK_JDBC_PASSWORD="$LIFEOS_POSTGRES_PASSWORD" \
  ./gradlew :labs:performance-lab:runPostgresQueryPlan
```

The probe emits the bounded PostgreSQL `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` plan and an
`indexBacked` observation without recording credentials or account data. A real database and
representative schema are prerequisites; no query-plan result is claimed by this smoke run.
