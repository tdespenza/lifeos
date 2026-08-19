# ADR-039: Deterministic aggregate-only financial insights

## Status

Accepted — deterministic foundation implemented; provider-backed explanations remain pending.

## Context

FR56 needs an assistant-facing financial insight path, but sending raw transactions or the user's
bearer token across service boundaries would expand the privacy and authorization surface. Finance
already owns the authoritative, bounded insights calculation and its `finance:insights-read`
authorization decision.

## Decision

Expose a dedicated workload-authenticated Finance projection at
`POST /api/v1/internal/assistant/finance-insights`. The request carries an Identity-issued subject
proof and a bounded currency/date range. Finance revalidates the proof and reads only its existing
aggregate insights query. The response contains totals, net amount, at most the existing bounded
category list, and explicit truncation/limitations; it contains no transaction rows.

The AI Assistant exposes `POST /api/v1/assistant/financial-insights`. It authenticates the caller,
calls Finance with a separate workload credential, preserves the aggregate boundary, and records
only redacted source/outcome facts. Calls are non-retrying, semaphore-bounded, timeout-bounded, and
fail closed on missing credentials or dependency failure. The caller's bearer credential is never
forwarded to Finance.

This deterministic response is a safe foundation, not a claim that a model provider is deployed or
that the service offers financial advice. Provider-backed narratives, forecasts, and any write
operation require separate contracts and authorization decisions.

## Consequences

- FR56 has a usable owner-scoped aggregate path with deterministic behavior and explicit limits.
- Finance remains the source of truth for financial authorization and calculations.
- The assistant cannot accidentally disclose unrelated transaction detail through this boundary.
- A deployment must provide `FINANCE_AI_ASSISTANT_WORKLOAD_TOKEN` to both services; blank defaults
  fail closed.
- Model-provider integration and explainability quality remain explicitly partial.

## Verification

The Finance projection has controller tests for successful aggregate reads and workload mismatch.
The Assistant has service, RestClient adapter, and HTTP contract tests covering bounded mapping,
authorization/unavailability classification, and the public response shape.
