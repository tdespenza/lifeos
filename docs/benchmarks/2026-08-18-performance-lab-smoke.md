# Performance Lab smoke result — 2026-08-18

This is a local correctness/measurement smoke run of the bounded Performance Lab, not a
production capacity claim. It uses the repository's Java 25 toolchain and is intentionally small
enough to run in CI or a developer checkout.

## Reproduction

```bash
JAVA_HOME="$JAVA_HOME" ./gradlew :labs:performance-lab:run --args='10000'
```

The runner uses a fixed seed, a 4,096-key map, 10,000 bounded lookups, and a 256-entry cache with
a deterministic hot/cold workload. It reports nanosecond percentiles for the map and hit/miss
counts for the cache. The captured run emitted:

```json
{"workload":"bounded-map-lookup","operations":10000,"checksum":636706365,"p50Nanos":83,"p95Nanos":167,"p99Nanos":292,"operationsPerSecond":5807200,"javaRuntime":"25.0.3+9-LTS","methodology":"seed=83877595270995,keySpace=4096"}
{"workload":"bounded-cache-lookup","operations":10000,"capacity":256,"hits":8022,"misses":1978,"finalSize":256,"hitRatio":0.8022,"operationsPerSecond":4861448,"javaRuntime":"25.0.3+9-LTS","methodology":"seed=16527118895647,hotKeySpace=64,coldEvery=5"}
```

The checksum and workload shape are deterministic; latency and operations/second vary with CPU,
JIT warmup, and host load. The cache result is a bounded local eviction fixture, not Redis
latency or production capacity. These results do not represent service, database, gRPC, GraphQL,
or virtual-thread throughput.
