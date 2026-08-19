# ADR-037: Confirmed AI Task Tool Execution

- Status: Accepted
- Date: 2026-08-18
- Decision owners: LifeOS platform
- Scope: `services/ai-assistant-service`, `services/task-goal-service`, FR58

## Context

The AI Assistant foundation previously exposed only non-executing tool proposals. Users need one
safe path from an explicitly confirmed proposal to a LifeOS action, but prompt text must never be
allowed to dispatch arbitrary URLs, commands, reflection targets, or unbounded cross-service writes.

## Decision

Implement only `DRAFT_TASK` execution through
`POST /api/v1/assistant/conversations/{conversationId}/tool-executions`:

1. The assistant authenticates the bearer, verifies conversation ownership, validates a fixed enum
   operation and bounded task fields, and requires a strict single `Idempotency-Key` plus an
   explicit `confirmed=true` flag.
2. The assistant calls a fixed Task/Goal path using a separate workload identity/token, an
   Identity-issued subject proof, a bounded timeout, and a semaphore. It never forwards the raw
   bearer token and never accepts a caller-selected URL or operation name.
3. Task/Goal locally reloads trusted ownership facts and performs its own Identity `task:create`
   decision before invoking its durable idempotency service. Matching retries therefore replay the
   same task snapshot and do not create duplicate tasks.
4. Every proposal rejection, downstream denial, success, or bounded failure is written as a
   redacted immutable assistant audit fact. Errors contain no task payload or downstream details.
5. `DRAFT_GOAL` now uses the same destination-specific authorization and mutation contract;
   `DRAFT_FINANCIAL_NOTE` remains proposal-only until its contract is reviewed.

## Consequences

The first side-effecting tool is executable without weakening the prompt safety boundary or moving
authorization into the assistant. The downstream service owns the mutation transaction and
idempotency record. A separate assistant confirmation ledger stores only a keyed request fingerprint
and operation, while Task/Goal owns the durable retry state.

The path is not a general agent runtime: it supports one operation, bounded JSON, one downstream
call, no automatic retry, no raw bearer forwarding, and fail-closed behavior when its workload
credential is absent or Task/Goal is unavailable.

## Verification

Contract tests cover explicit confirmation, stable error envelopes, workload/proof/idempotency
headers, denial mapping, and Task/Goal forwarding. Task/Goal tests cover workload rejection and
delegation of the subject proof and idempotency key to the existing authorization/idempotency
service.
