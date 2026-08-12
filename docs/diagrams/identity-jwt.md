# Story 1.5 — JWT issuance and refresh rotation

This document is the repository source of truth for the implemented Story 1.5 token contract. The
identity service issues short-lived access JWTs and high-entropy opaque refresh tokens. PostgreSQL
holds the durable session and token-family authority; Redis is not required for correctness.

## Token boundary

```mermaid
flowchart LR
    Client["Authenticated client"] --> Identity["Identity service"]
    Identity --> Access["Short-lived access JWT"]
    Identity --> Refresh["One-time opaque refresh token"]
    Identity --> Session["PostgreSQL session and family authority"]
    Service["Protected service"] --> Verify["Signature and claims check"]
    Verify -. "RSA production mode" .-> JWKS["Public JWKS endpoint"]
    Verify -. "HMAC local/test mode" .-> HMAC["Configured HMAC secret"]
    Verify --> IdentityVerify["Identity-service validation contract"]
    IdentityVerify --> SessionCheck["Durable active-session check"]
    SessionCheck --> Session
    Identity --> JWKS
```

## Issuance and rotation

```mermaid
sequenceDiagram
    actor Client
    participant Identity as Identity service
    participant Keys as Signing keys
    participant DB as PostgreSQL
    participant Replay as Replay record

    Client->>Identity: Login or refresh request
    Identity->>DB: Lock account or token family
    Identity->>Keys: Sign access JWT
    Identity->>DB: Store token digests and session metadata
    Identity->>Replay: Store bounded encrypted retry envelope
    DB-->>Identity: Commit
    Identity-->>Client: Access JWT and opaque refresh token

    Client->>Identity: Same refresh token and idempotency key
    Identity->>Replay: Read family/key record
    alt Matching first retry
        Replay-->>Identity: Return committed envelope once
        Identity-->>Client: Same successor response
    else New predecessor
        Identity->>DB: Consume predecessor and store successor atomically
        DB-->>Identity: Commit successor
        Identity-->>Client: New access and refresh tokens
    end

    Client->>Identity: Consumed token with mismatched or repeated key
    Identity->>DB: Revoke the entire token family
    Identity-->>Client: Generic authentication failure
```

## Protected-request validation

```mermaid
sequenceDiagram
    actor Client
    participant Service as Protected service
    participant JWKS as JWKS endpoint
    participant Identity as Identity service
    participant DB as Identity PostgreSQL

    Client->>Service: Bearer access JWT
    alt RSA production mode
        Service->>JWKS: Load public RSA key by kid
        Service->>Service: Verify signature and claims with JWKS key
    else HMAC local/test mode
        Service->>Service: Load configured shared HMAC secret
        Service->>Service: Verify signature and claims with HS256 secret
    end
    Service->>Identity: Validate bearer through internal auth contract
    Identity->>DB: Check session is active and digest matches
    Identity-->>Service: Validated subject or generic failure
    alt Active session
        Service-->>Client: Protected response
    else Invalid or revoked
        Service-->>Client: Authentication failure
    end
```

## Token-family lifecycle

```mermaid
flowchart LR
    subgraph Family[Token family status]
        Active["ACTIVE"]
        Active --> Expired["EXPIRED"]
        Active --> Revoked["REVOKED"]
    end
    subgraph Replay[Replay record state]
        Pending["PENDING"] --> Committed["COMMITTED"]
        Committed --> RetryConsumed["RETRY_CONSUMED"]
    end
    Active -. "rotation commits" .-> Pending
    Committed -. "matching retry" .-> RetryConsumed
    Committed -. "mismatch or second retry" .-> Revoked
    RetryConsumed -. "second or late reuse" .-> Revoked
    Active -. "idle or absolute deadline" .-> Expired
```

## Invariants

- JWTs use the configured issuer and audience, a stable subject and session id, an explicit
  authentication-method claim, and a bounded five-minute default expiry.
- Access-token and refresh-token values are never persisted in raw form. Only SHA-256 digests are
  stored; the one retry response is encrypted before it is persisted and expires after 30 seconds.
- A pessimistic family-row lock plus one conditional predecessor consumption gives one rotation
  linearization point. A mismatched replay revokes the family instead of minting another successor.
- JWT verification is only an early filter. Protected data requires an active durable session check,
  and a revoked session remains rejected after cache loss or restart.
- Family, session, and token-digest lookups use the declared database indexes; replay evidence is
  bounded per family. Deadline checks bound retry eligibility and replay-expiry checks, but do not
  delete expired `RefreshReplayRecord` rows from the database.

## Operational trade-offs

Asymmetric RSA signing and JWKS publication are the production configuration. The prior HMAC
configuration remains only as a local/test compatibility path and does not publish a symmetric key.
The database lock adds a bounded write round trip to refresh, but it makes concurrent reuse
deterministic and auditable. A configured replay-record bound revokes the family rather than
silently evicting evidence.
