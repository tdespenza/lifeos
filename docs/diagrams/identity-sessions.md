# Identity session management

Story 1.7 makes the identity service the user-facing authority for device and session management.
The access JWT authenticates the caller, but PostgreSQL remains the durable source of truth for
session ownership, expiry, and revocation. Redis stores only a revocation marker as an optional
read acceleration; a miss, eviction, restart, or outage always falls through to PostgreSQL.

## HTTP contract

All endpoints require `Authorization: Bearer <access-token>` and return `Cache-Control: no-store`.

| Method | Path | Behavior |
| --- | --- | --- |
| `GET` | `/api/v1/auth/sessions?cursor=...&limit=...` | Returns a bounded cursor page of the caller's unexpired sessions. |
| `POST` | `/api/v1/auth/sessions/{sessionId}/revoke` | Revokes one owned session; missing, foreign, and repeated targets are idempotent no-ops. |
| `POST` | `/api/v1/auth/sessions/revoke-others` | Revokes every active session except the authenticated current session. |

Session projections contain only the identifier, coarse device label/platform/browser/location,
authentication method, created/last-used/expiry timestamps, current-session flag, and revoked flag.
They never contain access-token hashes, refresh tokens, cookies, raw user agents, or network
addresses. The cursor is an opaque encoding of the indexed `(last_used_at, created_at, id)` order.

## Mutation sequence

```mermaid
sequenceDiagram
    actor User
    participant Client
    participant API as SessionController
    participant Authority as SessionManagementService
    participant DB as PostgreSQL
    participant Cache as Redis
    participant Audit as SecurityAuditService

    User->>Client: Open device settings
    Client->>API: GET /api/v1/auth/sessions
    API->>DB: Validate bearer JWT and durable session ownership
    API->>Authority: List account-scoped page
    Authority->>DB: Read bounded safe projection
    DB-->>API: SessionPage + nextCursor
    API-->>Client: 200 no-store

    User->>Client: Revoke a device
    Client->>API: POST /api/v1/auth/sessions/{id}/revoke
    API->>Authority: Revoke owned target
    Authority->>DB: Lock account and target; set revoked=true
    Authority->>Audit: Commit one redacted outcome in the same transaction
    Authority->>Cache: Publish revoked marker after commit
    API-->>Client: 204 no-store
```

## Invariants and failure behavior

- Every read and mutation is scoped by the authenticated account id. A client-supplied session id
  cannot bypass ownership checks or enumerate another account.
- Revocation is monotonic. JWT validation rejects revoked durable rows and refresh rotation rejects
  a revoked session before issuing a successor. Redis never contains an active allow decision.
- Account and session row locks use explicit bounded database timeouts. “Revoke others” locks the
  account and the affected rows, excludes the current session by id, and commits one audit outcome.
- Repeated revoke requests converge on the same durable state and return `204`; audit outcome codes
  are bounded (`REVOKED`, `NOOP`, or `REVOKE_OTHERS`) and contain no session identifiers.
- Device metadata is derived from a bounded user-agent classification. Missing or malformed input
  becomes `unknown`; raw headers and addresses are not persisted.
- Listing is `O(log n + k)` for a page of `k` indexed rows. Revoking the other active sessions is
  `O(log n + k)` for the affected account rows, with the existing account session-capacity policy
  bounding normal `k`.
