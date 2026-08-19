# ADR-029: Fail-Closed AI Assistant Foundation

- Status: Accepted
- Date: 2026-08-18
- Decision owners: LifeOS platform
- Scope: `services/ai-assistant-service`, FR53 and the foundation boundaries for FR55–FR59

## Context

LifeOS needs an AI life-assistant interaction surface with auditable decision handling. The target
experience eventually includes goal-planning recommendations, financial insights, journal/session
summaries, controlled tool use, and document-grounded answers. At the time of this decision,
there is no approved model-provider deployment, RAG/document retrieval integration, durable
conversation-memory policy, or safe cross-service mutation protocol.

Treating an absent model configuration as an opportunity to fabricate an answer would create an
unsafe product claim. Persisting raw prompts or completions by default would also create a new
high-sensitivity data store before consent, retention, encryption, and retrieval requirements are
settled.

## Decision

Build an independently deployable AI Assistant service with the following intentionally narrow
contract:

1. Authenticate public calls through Identity's workload-authenticated bearer-validation endpoint
   and apply local owner-constrained conversation lookup. The service has no current V2 decision
   action because Identity descriptors/workload binding are not changed by this ADR.
2. Persist only conversation metadata and immutable, audit-safe decision metadata in the isolated
   `lifeos_ai_assistant` database. Prompts, completions, bearer tokens, raw client addresses,
   account profile fields, financial records, and retrieved documents are not persisted.
3. Define a provider SPI and ship an opt-in OpenAI-compatible HTTP adapter, but install a disabled
   provider by default. The disabled provider returns a stable `AI_PROVIDER_NOT_CONFIGURED` failure
   before any model request can occur. The concrete adapter accepts only the redacted prompt,
   requires HTTPS for non-loopback endpoints, and bounds its virtual-thread executor, semaphore,
   timeout, cancellation, request/output limits, response bytes, and response shape checks.
4. Redact recognized PII before a provider boundary and reject recognized prompt-injection patterns
   and conservative token/character limit violations before invocation. Audit only fixed
   classifications, lengths, template IDs, an explicit no-retrieval marker, provider/model
   identifiers, output-summary classification, optional confidence, and keyed HMAC fingerprints.
5. Accept only a fixed allow-list of tool proposals. The service cannot invoke a shell, arbitrary
   URL, reflection target, or caller-selected destination. The confirmed DRAFT_TASK/DRAFT_GOAL execution
   path is defined separately by ADR-037; all other proposals remain `NOT_EXECUTED`.
6. Fail closed when Identity validation or audit persistence is unavailable. Return structured,
   content-free errors and correlation IDs rather than exception text.

## Consequences

### Positive

- The public interaction surface, owner isolation, safety bounds, redacted audit trail, and
  provider seam can be tested independently of a commercial model account.
- No deployment can accidentally produce synthetic responses when a provider credential is absent.
- Future provider/RAG work has a narrow redacted input object and an explicit timeout/concurrency
  boundary instead of direct controller access to a vendor SDK.
- Tool execution cannot become an accidental arbitrary-command or cross-service mutation channel.

### Negative and deferred work

- Valid requests return `503 AI_PROVIDER_NOT_CONFIGURED` until deployment explicitly enables and
  configures the bundled provider adapter or supplies another reviewed provider bean.
- The service is not conversation memory; it cannot supply history, RAG, citations, or personalized
  context. FR54 remains outside scope.
- FR55/FR56 cannot claim goal or finance grounding until authorized read-only projections are
  designed. FR57 cannot claim journal/session summaries until its source service exists.
- FR58's original proposal-only foundation is superseded for the single DRAFT_TASK path by
  ADR-037. Other tools still require user confirmation, destination-specific idempotency semantics,
  and the destination service's own authorization check before they can execute.

## Future authorization registration

Before this service makes Identity V2 decision calls, Identity must register workload
`ai-assistant-service` and the exact personal/self-only descriptors:

- `assistant:conversation-create` on `assistant-conversation` as `OWNED_CREATE`
- `assistant:conversation-read` on `assistant-conversation` as `OWNED_OBJECT`
- `assistant:conversation-request` on `assistant-conversation` as `OWNED_OBJECT`
- `assistant:tool-propose` on `assistant-conversation` as `OWNED_OBJECT`

All use `PERSONAL` tenant scope and `SUBJECT_ONLY` owner rule. No generic
`assistant:tool-execute` descriptor is approved by this decision; ADR-037 uses Task/Goal's existing
`task:create` authorization instead.

## Alternatives considered

### Return canned answers without a provider

Rejected. A canned response can look like a real recommendation and cannot truthfully carry model,
grounding, or audit provenance.

### Store raw prompts and completions to make a conventional chat history

Rejected for the foundation. It would require a specific consent, encryption, retention/deletion,
access, retrieval, and incident-response design before it is safe.

### Call Task/Goal or Finance directly from prompt text

Rejected. Prompt-derived remote mutation without a bounded schema, confirmation, idempotency, and
downstream authorization check is not an acceptable tool-execution model.
