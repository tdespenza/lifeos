# ADR-042: Confirmed AI Goal Tool Execution

## Status

Accepted — bounded foundation

## Context

The assistant already supports a confirmed `DRAFT_TASK` tool. Goal creation has the same durable
idempotency and owner/tenant authorization boundary in Task/Goal, but treating goals as proposal-only
left the advertised tool contract incomplete and encouraged a second mutation path.

## Decision

Enable `DRAFT_GOAL` through the existing confirmed tool-execution endpoint. The assistant validates the
conversation owner and explicit confirmation, records a keyed, raw-content-free confirmation ledger
entry, then calls a dedicated workload-authenticated Task/Goal goal projection. Task/Goal reauthorizes
`goal:create`, validates the subject proof, and performs the durable idempotency reservation and replay.
The assistant never forwards the user's bearer token.

The downstream response remains a bounded immutable snapshot. The public response uses the existing
tool envelope, but its `Location` is `/api/v1/goals/{id}` for `DRAFT_GOAL` and `/api/v1/tasks/{id}` for
`DRAFT_TASK`. Audit records retain the exact operation name and bounded outcome only.

`DRAFT_FINANCIAL_NOTE` is now a confirmed, non-mutating proposal. It records the same keyed,
raw-content-free confirmation fingerprint and returns `202 PROPOSED`; it does not write Finance or
invent a financial resource until a destination authorization/idempotency contract is approved.

## Consequences

- Goal creation has one authorization and idempotency implementation rather than an assistant-owned
  duplicate.
- A confirmation key cannot be silently reused for a different operation or payload; only a keyed
  fingerprint is persisted in the assistant ledger.
- Retries after a lost response converge on the same goal snapshot and location.
- The tool response is intentionally generic for compatibility; clients use `Location` and `status`
  to distinguish the created resource.
- Provider-backed planning and additional side-effecting tools remain future work.

## Verification

Focused controller, public assistant contract, Spotless, and Checkstyle tests cover workload rejection,
idempotency-header validation, goal dispatch, resource-specific location, and the existing task path.
