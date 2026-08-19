# Versioned gRPC contracts

`contracts:grpc-contracts` is the single generated-Java source for synchronous internal LifeOS
protocols. Every protobuf package and RPC service name includes `v1`; a breaking change creates a
new versioned package and leaves existing generated classes available until every consumer migrates.

The initial contract set defines narrow read-side shapes for Calendar availability, Finance period
summaries, Task planning projections, authorized Document excerpts, and Analytics dashboard
aggregation. It intentionally does not define a generic `execute` RPC or carry bearer tokens,
workload secrets, tenant claims, or arbitrary JSON. gRPC transport metadata and mTLS/workload
interceptors establish caller identity; `RequestMetadata` carries only the correlation ID needed for
trace continuity.

Task/Goal, Calendar, and Finance now expose opt-in metrics hosts on their internal gRPC ports. Each
requires mTLS with client certificates plus the deployment-owned
`x-lifeos-workload-token` metadata, validates the owner/tenant scope and period bound, and returns
bounded counts with an observation timestamp. Enable each host only with its service-specific
`*_GRPC_ENABLED=true`, `*_GRPC_TLS_ENABLED=true`, certificate/key/trust paths, and
`*_GRPC_WORKLOAD_TOKEN` settings; the default is disabled so local REST development is unchanged.

This remains a contract foundation, not a claim that all current REST adapters have already
migrated. Until each producer and consumer implements a generated server/stub pair with mTLS,
explicit deadlines, bounded fan-out, and tests, its existing bounded internal REST boundary remains
authoritative. The next consumers are Calendar optimization, Finance aggregation/forecasting,
document retrieval for RAG, and the GraphQL dashboard resolver.

Generated code is deterministic build output. Run:

```bash
./gradlew :contracts:grpc-contracts:check
```

The descriptor test ensures all emitted service names retain their explicit versioned namespace.
