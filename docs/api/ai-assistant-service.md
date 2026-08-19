# AI Assistant Service API

`ai-assistant-service` is an isolated, owner-scoped foundation for FR53 and the interaction,
safety, tool-policy, and audit boundaries needed by FR55–FR59. It includes an optional bounded
OpenAI-compatible and Qdrant adapters. Both are disabled by default and require an explicit
deployment configuration; this repository does not claim a hosted model or vector service.

## Runtime boundary

- Application port: `8090` (`AI_ASSISTANT_SERVICE_PORT`)
- Management port: `9090` (`AI_ASSISTANT_MANAGEMENT_PORT`), loopback by default
- Base path: `/api/v1/assistant`
- Public ingress: Gateway owns the authenticated `/api/v1/assistant` route and forwards it to this
  service through its ordinary bounded JSON/GET proxy. The route is not a provider deployment
  claim; an unconfigured provider still fails closed as described below.
- Every public endpoint requires `Authorization: Bearer <access-token>`. The service validates the
  bearer through the authenticated Identity workload endpoint, then performs a local
  `conversation.id + owner_account_id` lookup. Missing and cross-owner conversations both return
  the same `404 CONVERSATION_NOT_FOUND` envelope.

The service starts only when these deployment-owned secrets are supplied:

- `AI_ASSISTANT_DATASOURCE_URL`, `AI_ASSISTANT_DATASOURCE_USERNAME`, and
  `AI_ASSISTANT_DATASOURCE_PASSWORD`
- `AI_ASSISTANT_AUDIT_HMAC_SECRET`
- `IDENTITY_AI_ASSISTANT_WORKLOAD_TOKEN`

Identity maps the `ai-assistant-service` workload to the deployment-provided
`IDENTITY_AI_ASSISTANT_WORKLOAD_TOKEN` before a live process can call `/api/v1/auth/validate`.
No source-controlled fallback credential exists.

## Conversation metadata

### Create a conversation

`POST /api/v1/assistant/conversations`

```json
{
  "purpose": "GOAL_PLANNING"
}
```

Allowed purposes are `GENERAL`, `GOAL_PLANNING`, `FINANCIAL_INSIGHT`, and `SESSION_SUMMARY`.
The response is `201 Created`, with `Location` and `ETag` headers:

```json
{
  "id": "1e632544-7cf6-4d5d-8e60-a43d9db5a603",
  "purpose": "GOAL_PLANNING",
  "status": "ACTIVE",
  "version": 0,
  "createdAt": "2026-08-18T12:00:00Z",
  "updatedAt": "2026-08-18T12:00:00Z",
  "retainsPromptOrOutput": false
}
```

The conversation row contains only owner/purpose/state/timestamp metadata. It never stores a raw
prompt, completion, bearer token, account profile value, or retrieved document context.

Every safety/provider/tool decision also stores a deterministic `audit_hash_sha256` commitment over
the bounded redacted metadata and keyed fingerprints (never raw prompt, completion, bearer, or
address content). The field is nullable only for legacy rows created before migration V3; newly
written events always contain a lowercase 64-character SHA-256 value. This is an anchor-ready
commitment, not a claim that a blockchain provider is configured. The same transaction writes a
privacy-minimized `ai_audit_hash_outbox_event` envelope to the service-owned database. With
`AI_ASSISTANT_AUDIT_OUTBOX_RELAY_ENABLED=true`, a bounded leased relay publishes only that hash
envelope to `lifeos.ai.audit.hash.requested.v1`; broker failures are retried with capped backoff
and then retained in a local dead-letter table. The relay and Trust Ledger consumer are disabled by
default, and neither reports a blockchain receipt.

### Read metadata

`GET /api/v1/assistant/conversations/{conversationId}`

Returns the same metadata response and `ETag` for the authenticated owner.

### Read retained conversation history (explicit opt-in)

`GET /api/v1/assistant/conversations/{conversationId}/history`

The endpoint is owner-scoped and returns bounded `{role, content, createdAt}` messages only when
`AI_ASSISTANT_MONGODB_HISTORY_ENABLED=true` is explicitly enabled. The MongoDB adapter encrypts
each message with AES-256-GCM before persistence, stores only owner/conversation indexes and TTL
metadata in cleartext, prunes conversations to `AI_ASSISTANT_MONGODB_MAX_ENTRIES`, and applies
`AI_ASSISTANT_MONGODB_RETENTION_DAYS`. The user prompt written to history is the same PII-redacted
prompt sent to the provider. Disabled or unavailable storage returns `503 ASSISTANT_HISTORY_UNAVAILABLE`
and never falls back to PostgreSQL or an unbounded in-memory buffer.

For local development, start the loopback-only reference MongoDB with `--profile mongo`, generate
a base64-encoded 32-byte key, and set the `AI_ASSISTANT_MONGODB_*` variables in an untracked
environment file. The reference container has no authentication and is not a production boundary;
production requires authenticated TLS, private networking, consent/retention review, and managed
key rotation.

### Summarize retained conversation content

`POST /api/v1/assistant/conversations/{conversationId}/summary`

When history is enabled, the service selects at most the latest 16 retained messages and truncates
the transcript to 8,192 characters before applying the normal PII redaction and safety checks. The
configured provider receives only that bounded transcript and the response is returned once; the
summary is not written back to MongoDB. Disabled history, an empty retained conversation, or an
unavailable provider returns the existing fail-closed `503` envelope.

## Grounded document questions

`POST /api/v1/assistant/grounded-questions`

```json
{
  "query": "What did I write about the renewal date?",
  "maxOutputTokens": 256,
  "maxSources": 8
}
```

When `QDRANT_ENABLED=true`, retrieval uses the deterministic 32-dimensional embedding projection
and a Qdrant payload filter for the authenticated `owner_account_id`. The response returns only
document UUIDs as durable source identifiers; bounded snippets are sent to the explicitly enabled
provider and never written to the assistant database or logs. An empty index returns `200` with
`evidenceSufficient=false` and an explicit insufficient-evidence answer. A disabled/unreachable
Qdrant or provider returns `503 GROUNDED_ANSWER_UNAVAILABLE`; the service never falls back to an
unbounded PostgreSQL scan or invents an answer. Grounded questions and document summaries require
active Profile AI personalization consent with the `DOCUMENTS` context category; missing or
revoked consent returns `403 DOCUMENT_AI_NOT_AUTHORIZED` before vector retrieval.

`POST /api/v1/assistant/documents/{documentId}/summary` uses the same owner-filtered index and
returns `documentVersion`, bounded `sourceChunkIds`, and a generated summary. The authenticated
Profile must have active AI personalization consent with the `DOCUMENTS` context category; a
missing or revoked category returns `403 DOCUMENT_AI_NOT_AUTHORIZED` before retrieval. Older
indexed versions are ignored in favor of the highest indexed version, and chunks from another
document are discarded defensively. Missing, cross-owner, unindexed, or unavailable documents
produce the same bounded `503` response, so document existence is not disclosed. The local
deterministic provider labels its extractive output `local-deterministic/rules-v1`; this is a
bounded development workflow, not a claim of production model quality.

For the local reference stack, start Qdrant explicitly and opt both services in:
`docker compose --env-file infrastructure/docker-compose/.env -f infrastructure/docker-compose/docker-compose.yml --profile vector up -d qdrant`,
then set `QDRANT_ENABLED=true` and `DOCUMENT_VAULT_QDRANT_ENABLED=true` in the untracked service
environment. The local profile is loopback-only and unauthenticated; it is not a production
security boundary.

## Request a response

`POST /api/v1/assistant/conversations/{conversationId}/requests`

```json
{
  "message": "Help me think through the next milestone.",
  "maxOutputTokens": 128,
  "toolOperation": "DRAFT_TASK"
}
```

`message` is required. `maxOutputTokens` is optional and defaults to the deployment maximum.
`toolOperation` is optional and must be one of the exact strings `NONE`, `DRAFT_TASK`,
`DRAFT_GOAL`, or `DRAFT_FINANCIAL_NOTE`.

When the optional OpenAI-compatible provider is enabled and returns a response, the success
envelope is:

```json
{
  "conversationId": "1e632544-7cf6-4d5d-8e60-a43d9db5a603",
  "purpose": "GOAL_PLANNING",
  "content": "A provider response is returned once and is not retained by this service.",
  "estimatedInputTokens": 28,
  "maxOutputTokens": 128,
  "safetyFlags": ["PII_REDACTED"],
  "toolPlan": {
    "operation": "DRAFT_TASK",
    "executionState": "NOT_EXECUTED",
    "requiresUserConfirmation": true,
    "reason": "CROSS_SERVICE_MUTATION_TOOLS_NOT_ENABLED"
  },
  "providerId": "future-provider-id",
  "modelName": "future-model-name",
  "confidenceScore": 0.82,
  "contentRetained": false
}
```

The default provider mode is disabled and every otherwise valid request returns:

```http
503 Service Unavailable
```

```json
{
  "code": "AI_PROVIDER_NOT_CONFIGURED",
  "message": "AI generation is not configured in this deployment",
  "retryable": false,
  "correlationId": "..."
}
```

This is intentional fail-closed behavior, not a degraded synthetic answer.

For dependency-free local development and deterministic contract tests, set
`AI_ASSISTANT_PROVIDER_MODE=LOCAL_DETERMINISTIC`. This uses an extractive rules provider, returns a
bounded excerpt of the already-redacted prompt, and makes no model-quality claim or network call.

To opt into a reviewed local or remote OpenAI-compatible endpoint, set
`AI_ASSISTANT_PROVIDER_MODE=OPENAI_COMPATIBLE`, provide `AI_ASSISTANT_PROVIDER_BASE_URL` and
`AI_ASSISTANT_PROVIDER_MODEL`, and set `AI_ASSISTANT_PROVIDER_API_KEY` when the endpoint requires
one. Non-loopback endpoints must use HTTPS. The adapter sends only the safety-redacted prompt,
uses the configured provider timeout, and rejects response bodies larger than
`AI_ASSISTANT_PROVIDER_MAX_RESPONSE_BYTES` (262,144 bytes by default).

## Bounds and safety behavior

Default deployment bounds are deliberately small and can only be configured within hard validated
maximums:

| Control | Default | Hard maximum / behavior |
| --- | ---: | --- |
| Inbound body | 16,384 bytes | 65,536 bytes; chunked bodies are also counted |
| Inbound request timeout | 8 seconds | 60 seconds |
| Concurrent inbound requests | 64 | 512 |
| Message length | 4,096 Unicode code points | 16,384 |
| Conservative input estimate | 2,048 tokens | 4,096; one token per two code points |
| Requested output | 512 tokens | 2,048 |
| Concurrent generations | 16 | 128 |
| Provider timeout | 5 seconds | 30 seconds; work is cancelled on timeout |

Before a provider call, the service:

1. Redacts recognized email addresses, phone numbers, SSNs, and Luhn-valid payment-card numbers.
2. Rejects recognized prompt-injection instruction-boundary patterns rather than asking a model to
   adjudicate them.
3. Rejects character or conservative token estimate overruns.
4. Uses a fixed tool-operation allow-list. It performs no reflection, shelling out, URL dispatch,
   arbitrary tool execution, or remote mutation.

Recognized safety errors are structured and content-free: `PROMPT_REJECTED` (`422`),
`INPUT_LIMIT_EXCEEDED` (`413`), `OUTPUT_TOKEN_LIMIT_EXCEEDED` (`422`), and
`TOOL_OPERATION_NOT_ALLOWED` (`422`). Provider capacity/failure errors are bounded `503`/`504`
responses with safe error codes and no provider exception body.

## Audit model

Each authentication, conversation, safety, tool-policy, and provider decision writes an immutable
`assistant_request_audit_event` row. It contains only bounded classifications, purpose/template
metadata, bounded source document IDs (or an explicit `NONE` marker), input character/token counts, safety flags,
provider/model identifiers, a fixed output-summary classification, optional bounded confidence,
tool state, latency, correlation ID, and keyed HMAC fingerprints. It does not contain prompt text,
output text, bearer credentials, document context, personal financial data, or raw client addresses.

If the audit store cannot persist a safety-relevant decision, the service returns a fail-closed
`503 AI_REQUEST_UNAVAILABLE` response.

## Confirmed task tool execution

`POST /api/v1/assistant/conversations/{conversationId}/tool-executions` executes the currently
enabled side-effecting tools, `DRAFT_TASK` and `DRAFT_GOAL`. The caller must be the authenticated conversation owner,
must send exactly one visible-ASCII `Idempotency-Key` (1–128 characters), and must set
`confirmed: true` in the request body:

```json
{
  "operation": "DRAFT_TASK",
  "title": "Call dentist",
  "priority": 3,
  "dueAt": "2026-08-19T15:00:00Z",
  "confirmed": true
}
```

The response is the downstream Task/Goal snapshot with `201 Created`, `Location`, `ETag`, and
`Cache-Control: no-store`. Retries with the same key are resolved by Task/Goal's durable idempotency
record and return the same snapshot. The assistant sends an Identity-issued subject proof and a
separate workload credential to Task/Goal; it never forwards the user's bearer token. Task/Goal
performs its own `task:create` Identity decision and ownership checks before writing.

Unconfirmed requests return `409 TOOL_CONFIRMATION_REQUIRED`; unsupported operations return
`422 TOOL_OPERATION_NOT_ALLOWED`; downstream authorization denial returns `403 TOOL_NOT_AUTHORIZED`;
bounded downstream failure returns `503 TOOL_UNAVAILABLE` with `Retry-After: 1`. The workload token
is supplied through `TASK_GOAL_AI_ASSISTANT_WORKLOAD_TOKEN` and is blank/fail-closed by default.
`DRAFT_GOAL` uses the same explicit confirmation, workload-authenticated Task/Goal authorization,
durable idempotency, and versioned snapshot semantics as `DRAFT_TASK`; its response `Location` points
to `/api/v1/goals/{id}`. `DRAFT_FINANCIAL_NOTE` is a confirmed, non-mutating proposal: it records
only the keyed confirmation fingerprint, returns `202 Accepted` with `status: PROPOSED`, and
performs no Finance write until a destination resource and authorization descriptor are approved.

For confirmed task/goal calls, the assistant also records a confirmation-ledger row containing only
the conversation/owner, operation, a SHA-256 idempotency-key hash, and a SHA-256 request fingerprint.
Reusing a key for a different operation or payload returns `409 TOOL_CONFIRMATION_CONFLICT`; matching
retries reuse the same ledger entry and continue through Task/Goal's durable mutation idempotency.

## Goal-planning recommendations

`POST /api/v1/assistant/recommendations` returns a bounded deterministic ranking of the
authenticated user's active tasks and goals:

```json
{
  "maxResults": 5
}
```

The assistant calls a fixed Task/Goal workload boundary. Task/Goal reauthorizes both `task:list`
and `goal:list` against the Identity-issued subject proof, reloads owner/tenant-scoped rows, removes
terminal work, and returns at most eight bounded facts. The assistant ranks overdue work first,
then due date, priority, and UUID for deterministic tie-breaking. It returns `source: TASK_GOAL`
and never sends these facts to a model provider. A missing or denied projection fails closed with
`503 RECOMMENDATIONS_UNAVAILABLE` or `403 RECOMMENDATIONS_NOT_AUTHORIZED`.

## Deterministic financial insights

`POST /api/v1/assistant/financial-insights` returns a bounded, owner-scoped Finance aggregate for
an inclusive date range and currency:

```json
{
  "currency": "USD",
  "from": "2026-01-01",
  "to": "2026-01-31"
}
```

The date range is limited to 366 days. The response contains only period totals, net amount,
bounded category aggregates, and explicit `truncated`/`limitations` fields. It never includes raw
transactions or forwards the caller's bearer token:

```json
{
  "currency": "USD",
  "from": "2026-01-01",
  "to": "2026-01-31",
  "incomeMinor": 250000,
  "expenseMinor": 125000,
  "netMinor": 125000,
  "categories": [],
  "truncated": false,
  "limitations": [],
  "source": "FINANCE"
}
```

The assistant calls Finance through a separate workload-authenticated internal projection. Finance
revalidates the Identity-issued subject proof and `finance:insights-read` before reading. Missing
or denied projection access returns `403 FINANCE_INSIGHTS_NOT_AUTHORIZED`; bounded dependency,
timeout, or storage failures return `503 FINANCE_INSIGHTS_UNAVAILABLE`. This is a deterministic
aggregate foundation, not a provider-backed financial explanation or forecast.

## Consent-gated journal summaries

`POST /api/v1/assistant/journal-summary` creates a bounded deterministic digest of the caller's
Profile journal entries:

```json
{
  "maxEntries": 5,
  "maxCharacters": 4000
}
```

Both fields are optional; limits are 10 entries and 16,384 characters. Profile revalidates the
Identity-issued subject proof and requires explicit AI personalization consent with the `JOURNALS`
context category before returning entries through a separate workload-authenticated projection.
The assistant returns the source journal UUIDs, a truncated flag, and limitations. It forwards no
user bearer token and records only redacted source/outcome audit facts. Missing consent returns
`403 JOURNAL_SUMMARY_NOT_AUTHORIZED`; disabled or unavailable encrypted journal storage returns
`503 JOURNAL_SUMMARY_UNAVAILABLE`.

The digest is intentionally deterministic and provider-free: each bounded entry contributes its
title and first sentence. Session transcript integration and provider-backed summaries remain
partial until Media and a reviewed model deployment supply those contracts.

## Analytics recommendations

`POST /api/v1/assistant/analytics-recommendations` maps bounded Analytics productivity signals to
non-mutating guidance:

```json
{
  "periodDays": 30
}
```

The period is limited to 90 days. Each result contains a stable signal key, score, evidence metric
keys, the exact `periodDays` calculation window, and a short deterministic message. Before reading Analytics, AI retrieves Profile
personalization through a separate workload projection and requires consent, personalization enabled,
and the `ANALYTICS` context category. AI then calls Analytics through a separate workload credential
and an HMAC proof bound to the Identity-issued account/session; raw event payloads and bearer tokens
never cross the boundary. Missing or denied projection access returns `503
ANALYTICS_RECOMMENDATIONS_UNAVAILABLE` or `403 ANALYTICS_RECOMMENDATIONS_NOT_AUTHORIZED`.
Provider-backed narratives remain partial.

## Explicitly pending

- A production model-provider deployment, credential lifecycle, output schema policy, and
  provider-specific content safeguards. The bounded OpenAI-compatible adapter is available for
  explicitly configured local/deployment endpoints, but no provider is enabled by default.
- MongoDB retained conversation content now has an explicit encrypted, bounded, opt-in adapter and
  owner-scoped read endpoint. Production consent UX, authenticated managed MongoDB, key rotation,
  and retention governance remain pending. The bounded Qdrant retrieval and source-attribution
  contract is implemented, but production embedding quality, collection provisioning,
  model-provider approval, and broad cross-service ingestion remain.
- Provider-backed explanations and forecast narratives over the deterministic Goal/Task and Finance
  projections remain pending. The current recommendation and financial-insight paths are explicit
  bounded deterministic foundations.
- Session transcript integration and provider-backed journal/session summaries remain pending; the
  current Profile journal path is an explicit consent-gated deterministic foundation.
- Session/journal source integration for FR57.
- Additional cross-service mutation tools and a separate assistant-side confirmation ledger for
  FR58. The bounded DRAFT_TASK and DRAFT_GOAL paths, assistant confirmation ledger, and downstream
  durable idempotency/authorization boundary are implemented.
- Provider-backed goal-planning recommendations and broader goal/task context remain pending; the
  current deterministic ranking is an explicit bounded fallback rather than a model-quality claim.
- Production operational deployment evidence, including an Identity workload-token registration
  and a reviewed model-provider deployment.

## Future Identity V2 boundary (not registered by this module)

The current implementation validates the bearer through Identity and enforces local owner scope.
When Identity ownership is allocated, register workload `ai-assistant-service` with policy version
`v2` and exact descriptors below before changing this service to call authorization decisions:

| Action | Resource type | Shape | Tenant scope | Owner rule |
| --- | --- | --- | --- | --- |
| `assistant:conversation-create` | `assistant-conversation` | `OWNED_CREATE` | `PERSONAL` | `SUBJECT_ONLY` |
| `assistant:conversation-read` | `assistant-conversation` | `OWNED_OBJECT` | `PERSONAL` | `SUBJECT_ONLY` |
| `assistant:conversation-request` | `assistant-conversation` | `OWNED_OBJECT` | `PERSONAL` | `SUBJECT_ONLY` |
| `assistant:tool-propose` | `assistant-conversation` | `OWNED_OBJECT` | `PERSONAL` | `SUBJECT_ONLY` |

The confirmed DRAFT_TASK endpoint does not require an `assistant:tool-execute` Identity action: it
uses the existing conversation owner check plus Task/Goal's workload-authenticated `task:create`
decision and durable idempotency boundary. Register additional assistant tool descriptors only when
their destination-specific confirmation and authorization contracts are approved.
