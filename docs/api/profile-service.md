# profile-service API

Base URL through the gateway: `http://localhost:8080`

Service URL for local direct development: `http://localhost:8083`

Management URL (loopback by default): `http://127.0.0.1:9083`

`profile-service` is the PostgreSQL system of record for a user's personal profile, validated
preferences, privacy controls, AI-personalization consent, and explicit household memberships. It
owns the `lifeos_profile` database only; it neither joins Identity tables nor treats a client JSON
owner or tenant value as authoritative.

## Authentication and authorization

Every public operation requires:

```http
Authorization: Bearer <LifeOS access token>
```

The service validates that bearer through the workload-authenticated Identity endpoint, then obtains
an explicit Identity decision for its exact action. Identity validates session/revocation state and
the self/tenant capability; Profile rechecks locally persisted owner/tenant facts and household
membership permissions. A timeout, invalid workload response, unavailable audit store, or full
outbound admission semaphore fails closed.

There is deliberately no `/profiles/{accountId}` endpoint. `/api/v1/profiles/me` is self-only, even
for a tenant administrator. Household reads first require Identity capability and then one of the
membership's finite local permissions. Missing and cross-scope households have the same `404`
representation, so a guessed ID cannot be used for enumeration.

| Status | Meaning |
| --- | --- |
| `401` | Missing, invalid, expired, revoked, or malformed bearer credential; `WWW-Authenticate: Bearer` is present. |
| `403` | Identity denied the caller's action. |
| `404` | A profile-dependent representation is absent, or a household is absent or outside the caller's local scope. |
| `503` | Identity, audit, idempotency, or a bounded dependency cannot make a safe decision; response includes `Retry-After: 1` where retryable. |

The service audits authentication, authorization, local household-scope, mutation, and replay
outcomes. Audit rows use a correlation ID, account reference where available, bounded outcome code,
and HMACed client-address fingerprint; they do not store profile fields, relationship labels,
bearer values, raw client addresses, request payloads, or idempotency keys.

## Conditional mutation and retry contract

All writes require one opaque, case-sensitive `Idempotency-Key` matching
`[A-Za-z0-9][A-Za-z0-9._~-]{0,127}`. The durable key scope is the validated caller, personal tenant,
and operation. Raw keys and request payloads are not persisted: the service stores domain-separated
HMAC-SHA-256 digests plus the committed public JSON response snapshot.

Creates also require exactly `If-None-Match: *`. Replacements and membership changes require exactly
one strong numeric `If-Match` tag such as `"4"`; weak tags, `*`, lists, duplicates, malformed tags,
and omitted values are rejected. Successful responses contain `ETag: "<new-version>"` and
`Idempotent-Replayed: false`. A matching authorized retry returns the original immutable body,
status, `ETag`, and `Location` (when created), with `Idempotent-Replayed: true`—it never rereads a
later mutable representation.

The reservation and successful mutation commit atomically. A process interruption after reservation
but before commit remains safely resumable by the same authenticated request. A deterministic
business rejection, such as a stale version or an existing profile, removes its uncompleted
reservation so it cannot become a permanently PENDING retry. Reusing the same scoped key with a
different request fingerprint returns `409` without disclosing the earlier request.

| Status | Mutation condition |
| --- | --- |
| `200` | Successful replacement, membership change, deletion, or exact replay of one. |
| `201` | Successful create or exact replay; includes the original `Location`. |
| `400` | Invalid input, conditional header, or idempotency-key shape. |
| `409` | Key reuse with a distinct decoded request, or invalid household membership change. |
| `412` | A profile or target representation is no longer current, including an attempted second self-profile create. |
| `428` | Required `If-Match` or `If-None-Match: *` is absent. |

## Personal profile

### `POST /api/v1/profiles/me`

Creates the caller's one profile in its personal tenant. The profile ID is a fresh UUID distinct
from the account ID, so the `(owner_account_id, tenant_id)` invariant remains valid if the tenancy
model later permits multiple tenant scopes.

```json
{
  "displayName": "Ada Lovelace",
  "locale": "en-GB",
  "timeZone": "Europe/London",
  "pronouns": "she/her",
  "bio": "Mathematician"
}
```

`displayName` is 1–120 characters, `locale` is a valid BCP 47 language tag, `timeZone` is a valid
IANA zone, `pronouns` is at most 80 characters, and `bio` is at most 1,000 characters. On success,
the service creates default preferences, private privacy settings, and disabled AI personalization
settings in the same transaction, returns `201`, and sets `Location: /api/v1/profiles/me`.

### `GET /api/v1/profiles/me`

Returns only the authenticated caller's `ProfileResponse` with an `ETag`.

### `PUT /api/v1/profiles/me`

Full replacement using the same JSON shape and validation as create. Send `If-Match` and
`Idempotency-Key`; a successful response is `200` with the updated version.

## Validated settings

Every endpoint below is self-only. Reads return an `ETag`; updates use the conditional mutation
contract above.

| Endpoint | Representation / allowed values |
| --- | --- |
| `GET`, `PUT /api/v1/profiles/me/preferences` | `theme`: `SYSTEM`, `LIGHT`, or `DARK`; `weekStart`: `MONDAY` or `SUNDAY`; `dailyDigestEnabled`; `defaultGoalHorizonDays` from 1 through 365. Defaults are `SYSTEM`, `MONDAY`, `true`, and `30`. |
| `GET`, `PUT /api/v1/profiles/me/privacy` | `profileVisibility`: `PRIVATE` or `HOUSEHOLD`; `shareActivityWithHousehold`; `allowHouseholdDirectory`. Defaults are private and both sharing controls are false. |
| `GET`, `PUT /api/v1/profiles/me/ai-personalization` | `consentGranted`; `personalizationEnabled`; `allowedContextCategories` selected from `PROFILE`, `PREFERENCES`, `GOALS`, `JOURNALS`, `ANALYTICS`, and `DOCUMENTS`. A nonempty category set requires both consent and personalization enabled. New profiles have no consent, personalization disabled, and no categories. |

The AI endpoint persists revocable consent and permitted categories only. This service does not send
profile data to an AI provider or imply that an AI provider integration exists.

## Encrypted journals and notes (optional)

The Profile service exposes owner-scoped free-form journal/notes storage only when
`PROFILE_JOURNAL_MONGODB_ENABLED=true` is explicitly configured. Each entry is encrypted with
AES-256-GCM before it is written to MongoDB; title and content are never used as query keys, and
the owner UUID is the only lookup scope. Storage is bounded by a per-owner entry cap, a UTF-8
content limit, and a fixed list page size. Disabled or unavailable MongoDB returns `503` with
`Retry-After: 1` rather than falling back to PostgreSQL or plaintext storage.

| Endpoint | Contract |
| --- | --- |
| `POST /api/v1/profiles/me/journal` | Requires `Idempotency-Key`; creates an encrypted `{title, content}` entry and returns `201` with `Location`, `ETag`, and `Idempotent-Replayed` (`false` for a new write, `true` for an exact replay). |
| `GET /api/v1/profiles/me/journal?limit=20` | Returns at most 50 owner-scoped entries, newest updated first. |
| `GET /api/v1/profiles/me/journal/{entryId}` | Returns one owner-scoped entry or the same generic `404` for missing/cross-owner IDs. |
| `PUT /api/v1/profiles/me/journal/{entryId}` | Requires a strong numeric `If-Match` and `Idempotency-Key`; returns the new `ETag` and `Idempotent-Replayed` marker. |
| `DELETE /api/v1/profiles/me/journal/{entryId}` | Requires a strong numeric `If-Match` and `Idempotency-Key`; marks the entry deleted and returns `204` with an `Idempotent-Replayed` marker. |

The assistant may request a bounded projection at
`POST /api/v1/internal/assistant/journals` using its separate workload identity/token and an
Identity-issued subject proof. Profile rechecks `profile:ai-personalization-read`, requires
`consentGranted=true`, `personalizationEnabled=true`, and the `JOURNALS` context category, then
returns at most 10 entries and 16,384 characters. Missing credentials return `401`; missing consent
or authorization returns `403`. The projection is disabled by default when
`PROFILE_AI_ASSISTANT_WORKLOAD_TOKEN` is blank and never logs or forwards the caller's bearer.

The local Compose `mongo` profile is an unauthenticated loopback-only development fixture. A
production deployment must provide authenticated TLS MongoDB, managed key material and rotation,
retention/backup policy, and explicit consent UX before enabling this boundary.

## Households and family relationships

### `POST /api/v1/households`

Creates a caller-owned household with a nonblank name up to 120 characters. It requires
`If-None-Match: *` and `Idempotency-Key`, returns `201`, and assigns the creator a `SELF`
membership with all finite permissions:

- `HOUSEHOLD_READ`
- `MEMBERS_READ`
- `MEMBERS_MANAGE`

### `GET /api/v1/households/{householdId}`

Requires `HOUSEHOLD_READ`. The response is `{ id, name, version, createdAt, updatedAt }` with an
`ETag`.

### `GET /api/v1/households/{householdId}/members`

Requires `MEMBERS_READ`. It returns the explicitly visible member account reference,
`relationshipType` (`SELF`, `SPOUSE`, `PARTNER`, `CHILD`, `PARENT`, `SIBLING`, or `OTHER`), finite
permission set, version, and timestamps.

### Membership mutations

All require `MEMBERS_MANAGE`, `If-Match`, and `Idempotency-Key`, and return the updated household
with a new `ETag`.

| Endpoint | Request / behavior |
| --- | --- |
| `POST /api/v1/households/{householdId}/members` | `{ "accountId": "<UUID>", "relationshipType": "SPOUSE", "permissions": ["HOUSEHOLD_READ"] }`; duplicate membership is `409`. |
| `PUT /api/v1/households/{householdId}/members/{accountId}/permissions` | Replaces nonempty finite `permissions`; the creator's immutable owner permission set cannot be changed. |
| `DELETE /api/v1/households/{householdId}/members/{accountId}` | Removes a non-owner membership; the creator cannot be removed. |

The target account ID is an opaque relationship reference; Profile does not expose an account lookup
endpoint or use a success/failure distinction to reveal whether a non-member account exists.

## Persistence and operations

Production Flyway migration
[`V1__create_profile_service_schema.sql`](../../services/profile-service/src/main/resources/db/migration/V1__create_profile_service_schema.sql)
creates the Profile-owned tables, owner/tenant uniqueness constraint, membership permission table,
idempotency snapshots, and redacted audit table. H2 tests use the separately maintained equivalent
under `src/test/resources/db/migration-h2`.

The service enables Java virtual threads. Direct inbound traffic is bounded by a 64 KiB default body
limit, a 128-request non-waiting semaphore, and a 10-second configurable Tomcat socket/upload
deadline. Identity validation/decision calls have explicit connection/read deadlines and a fair,
bounded outbound semaphore. Deployments must provide nonblank
`PROFILE_DATASOURCE_URL`, `PROFILE_DATASOURCE_USERNAME`, `PROFILE_DATASOURCE_PASSWORD`,
`PROFILE_IDEMPOTENCY_SECRET`, `PROFILE_AUDIT_CLIENT_FINGERPRINT_SECRET`, and
`IDENTITY_PROFILE_WORKLOAD_TOKEN`; source control supplies no secret or datasource fallback.

Actuator exposes `health`, `health/liveness`, `health/readiness`, `info`, and `prometheus` only on
the loopback management listener. ECS structured logs, Micrometer Prometheus metrics, and OpenTelemetry
trace export are configured; a production deployment still needs its own secret manager, TLS/mTLS,
network policy, retention, backups, alerts, and telemetry backend.
