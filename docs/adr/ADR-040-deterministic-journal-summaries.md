# ADR-040: Consent-gated deterministic journal summaries

## Status

Accepted — journal foundation implemented; session transcript and provider-backed summaries remain partial.

## Context

FR57 needs source-linked summaries of personal journals and sessions. Profile owns encrypted journal
content, while AI Assistant owns the public summary contract. Sharing raw journal content without a
revocable consent boundary would violate the intended privacy model, and a model provider is not
deployed in every environment.

## Decision

Add `JOURNALS` as an explicit AI personalization context category. Profile exposes a bounded,
workload-authenticated internal projection at `POST /api/v1/internal/assistant/journals`. It
reauthorizes the Identity-issued subject proof, requires active consent/personalization and the
`JOURNALS` category, then returns at most 10 entries and 16,384 characters. The projection is
disabled when `PROFILE_AI_ASSISTANT_WORKLOAD_TOKEN` is blank and never accepts or forwards a user
bearer token.

AI Assistant exposes `POST /api/v1/assistant/journal-summary`. It calls the projection with a
separate workload credential and emits a deterministic digest composed of bounded titles and first
sentences, plus source UUIDs and explicit truncation/limitations. It uses no retries, has bounded
concurrency/timeouts, and records only redacted audit facts.

## Consequences

- FR57 has an executable, owner-scoped, consent-gated journal path without requiring a provider.
- Revoking consent immediately prevents future projections; no PostgreSQL fallback is used when
  encrypted MongoDB is disabled or unavailable.
- The deterministic digest is not a claim of model-quality summarization or financial/session advice.
- Media transcript/session source integration, provider deployment, and generated quality remain
  separate work.

## Verification

Profile controller tests cover workload rejection, consent gating, and bounded content. AI tests
cover deterministic source-linked output, disabled credentials, adapter mapping, and the public HTTP
contract. Full Profile and AI service checks include unit, integration, contract, formatting, and
static checks.
