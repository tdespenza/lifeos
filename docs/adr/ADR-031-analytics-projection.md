# ADR-031: bounded analytics projection

## Context

Analytics must support a useful dashboard without copying private notification content or making
every dashboard request fan out across all bounded contexts.

## Decision

Use an account/tenant-scoped metric snapshot read model with a durable CloudEvent inbox. Metric keys,
periods, and values are strictly bounded. Optional Kafka consumption is at-least-once with event-ID
dedupe; the service is still useful with explicit workload-authenticated metric writes while eventing
is provisioned.

The gateway proves its authenticated subject with an HMAC over the method, path, account, and
session. Analytics rejects direct requests without that proof. Raw event bodies, contact endpoints,
provider responses, and bearer tokens are never persisted.

## Consequences

Dashboard reads are O(m) in the bounded number of returned metrics and do not depend on live service
fan-out. The projection is intentionally not a general data warehouse: complete event coverage,
recommendations, retention policy, and client-specific charts remain later work.
