# ADR-049: Explicit Media action-item confirmation into TaskGoal

## Status

Accepted for the bounded local foundation.

## Context

Media can deterministically extract a small, owner-scoped list of action items from an explicitly
supplied post-session transcript. Automatically creating tasks would turn an unreviewed artifact into
a user-visible mutation and would make provider retries unsafe. The TaskGoal service owns task
authorization, ownership facts, and task idempotency; Media owns the artifact and the confirmation
boundary.

## Decision

Expose one versioned Media command, `POST /api/v1/media/sessions/{id}/post-session/tasks`. The caller
must present the owner bearer, the artifact's strong `If-Match`, an idempotency key, and an exact
action-item string. Media authorizes `media:session-update` and sends only the validated Identity
subject proof to TaskGoal through a separate `media-service` workload credential. TaskGoal validates
that workload, re-authorizes `task:create`, and derives owner/tenant facts locally; it accepts no
owner or tenant fields. Both sides retain durable request/response snapshots. The local and remote
command keys are deterministic digests of the session and action item (not the caller's fresh retry
key), so a duplicate confirmation cannot create a second task; a changed priority/deadline is
rejected by the stored request fingerprint.

The boundary is deliberately a command, not a shared database relation: the task response contains
the canonical Task ID, while Media persists no cross-service foreign key in this foundation. A failed
TaskGoal call removes the pending Media reservation where safe and returns `503`; the caller retries
the same key. No raw bearer token, transcript, or action-item list is forwarded to TaskGoal.

## Consequences

Users must explicitly confirm each item. This prevents surprise writes and preserves clear ownership,
but it requires clients to render a confirmation affordance. Provider-grade transcription, task/session
cross-service links, and production workload secret provisioning remain separate work.
