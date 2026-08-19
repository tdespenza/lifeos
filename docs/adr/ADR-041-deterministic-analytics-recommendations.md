# ADR-041: Deterministic Analytics recommendations

## Status

Accepted — bounded recommendation foundation implemented; provider-backed narratives remain partial.

## Context

Analytics owns a privacy-minimized read model of productivity metrics. FR79 needs the assistant to
use those signals without copying raw event payloads, accepting an untrusted tenant, or making a
mutation. The repository does not require a hosted model provider in local or test deployments.

## Decision

Analytics exposes `POST /api/v1/analytics/internal/assistant-insights`. The request carries the
Identity-issued account/session proof and a bounded 1–90 day period. Analytics requires a separate
AI workload credential and validates the HMAC proof over the exact method/path/account/session before
reading its existing `productivityInsights` projection. The response contains at most five stable
signal keys, scores, evidence metric keys, and limitations.

AI Assistant exposes `POST /api/v1/assistant/analytics-recommendations`. It calls Analytics with a
bounded non-retrying client, maps signals to short non-mutating messages, and records redacted audit
facts. No raw event payloads or user bearer credentials cross the boundary.

## Consequences

- FR79 has an executable owner/session-bound deterministic path with explainable evidence keys.
- Analytics remains the source of truth for metric calculations and scope.
- A deployment must provide `ANALYTICS_AI_ASSISTANT_WORKLOAD_TOKEN` and the shared proof secret to
  both services; blank defaults fail closed.
- Profile consent is required before the Analytics projection is read: consent must be granted,
  personalization must be enabled, and the `ANALYTICS` context category must be allowed.
- Model-provider narratives remain future work.

## Verification

Analytics controller tests cover workload/proof rejection and bounded insight mapping. AI tests cover
signal-to-message mapping and the public HTTP response. Static checks and the repository aggregate
check run for both modules.
