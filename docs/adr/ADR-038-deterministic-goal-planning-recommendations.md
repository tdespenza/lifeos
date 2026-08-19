# ADR-038: Deterministic Goal-Planning Recommendations

- Status: Accepted
- Date: 2026-08-18
- Decision owners: LifeOS platform
- Scope: `services/ai-assistant-service`, `services/task-goal-service`, FR55

## Context

FR55 needs a useful planning response even when no model provider is configured. Sending Task/Goal
rows directly to a provider would also create a new privacy and authorization boundary. The existing
Task/Goal service already owns the Identity decision and local owner/tenant facts, so the assistant
can consume a narrow projection instead.

## Decision

Expose a workload-authenticated Task/Goal planning snapshot and a public assistant recommendation
endpoint:

1. Task/Goal authorizes both `task:list` and `goal:list` for the Identity-issued subject proof,
   queries only the matching account and tenant, excludes terminal rows, and returns at most eight
   facts containing type, UUID, title, status, priority, and due date.
2. The assistant uses a fixed workload credential and bounded semaphore/timeout. No user bearer is
   forwarded, and the caller cannot select a URL, tenant, query, or resource ID.
3. Ranking is deterministic: overdue first, then due date, priority, and UUID. Results are capped
   at eight and carry a bounded reason. This is a transparent planning fallback, not a model-quality
   or personalized-AI claim.
4. Every successful, denied, or unavailable projection is recorded as a redacted audit fact with
   source UUIDs only. Missing credentials, authorization denial, malformed snapshots, and transport
   failures fail closed.

## Consequences

Users get actionable, explainable planning suggestions without a provider deployment. Task/Goal
remains the source of truth for ownership and policy. Provider-backed recommendations, richer
constraints, and broader context remain future work and cannot silently bypass this boundary.
