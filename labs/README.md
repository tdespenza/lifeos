# LifeOS Engineering Labs

Labs are bounded, reproducible teaching artifacts and are never production service boundaries.

| Lab | Scope | Verification |
| --- | --- | --- |
| `algorithms-lab` | 17 product-backed algorithms/data structures | `./gradlew :labs:algorithms-lab:check` |
| `concurrency-lab` | platform/virtual threads, structured concurrency, scoped values | `./gradlew :labs:concurrency-lab:test` |
| `distributed-systems-lab` | resilience and distributed-data patterns | run disposable local fakes only |
| `performance-lab` | bounded Java benchmark plus k6/JFR/JVM/query/REST/gRPC/GraphQL experiments | `./gradlew :labs:performance-lab:test` |
| `blockchain-lab` | Merkle, Besu/Web3j, indexing, credentials, consensus | use disposable local keys/network |
| `ai-lab` | RAG, providers, tools, evaluation, audit | use synthetic consented fixtures |
| `system-design-lab` | ten named architecture mini-systems | `bash labs/system-design-lab/verify.sh` |
