# Mini-system title

Design exercise only — this is a proposed architecture, not a production deployment. State the
workload, trust boundary, and constraints before selecting components.

## Requirements

- **Users and use cases:** Name the caller, principal, and critical read/write flow.
- **Load and bounds:** State expected and burst rate, payload/request cap, retention, and one latency
  target. Mark every value as an assumption unless it came from a measured workload.
- **Correctness:** State ordering, consistency, idempotency, and deletion expectations.
- **Out of scope:** Name intentionally excluded features so they do not become accidental promises.

## API shape

Document public and internal operations with method, path/topic, required authentication,
idempotency/precondition headers, request/response fields, status behavior, and pagination or
streaming limits. Avoid opaque "do work" endpoints.

## Data model

Identify the source-of-truth records, keys, unique constraints, retention/lifecycle fields, and
derived projections. Call out which data is mutable, encrypted, or prohibited from logs/events.

## Scaling and partitioning

State the partition key, hot-key strategy, replica/cache role, index strategy, bounded queues, and
how a new partition is introduced or data is rebalanced. Explain which operations are cross-shard.

## Bottlenecks and tradeoffs

Name the likely first saturation point, the chosen consistency/latency/cost tradeoff, and a rejected
alternative. Include a bounded degradation mode rather than assuming unlimited capacity.

## Failure and recovery

Describe dependency timeouts, retry eligibility/backoff, idempotent replay, dead-letter or quarantine
handling, repair/reconciliation, and recovery objectives. Acknowledge where a caller must retry.

## Observability

List low-cardinality metrics, structured audit/log fields, traces, health/readiness checks, and at
least one alert based on error, saturation, lag, or data-integrity symptoms. Do not place raw user
content or identifiers in metric labels.

## Security and privacy

Specify authentication/authorization scope, input validation, abuse controls, encryption/key
boundaries, tenant isolation, retention/deletion behavior, and non-enumerating error semantics.
