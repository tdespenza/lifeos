# Story 1.2 — First-party login diagrams

These diagrams describe the implemented `POST /api/v1/auth/login` flow. The identity service is
the policy and session authority; PostgreSQL is the durable store, while Redis is limited to
distributed attempt counters. Refresh-token rotation, asymmetric signing/JWKS, and user-facing
session revocation remain Story 1.5 scope.

## Use-case view

```mermaid
flowchart LR
    User([Registered user])
    Client["REST client"]
    Login(("Submit email/password"))
    RateLimit(("Apply distributed rate limit"))
    Verify(("Verify Argon2id credential"))
    Session(("Create bounded session"))
    Audit(("Record redacted outcome"))
    User --> Client --> Login
    Login --> RateLimit
    RateLimit --> Verify
    Verify --> Session
    RateLimit --> Audit
    Verify --> Audit
    Session --> Audit
```

## Sequence view

```mermaid
sequenceDiagram
    actor User
    participant API as LoginController
    participant App as LoginService
    participant Redis as RedisLoginRateLimiter
    participant DB as PostgreSQL
    participant Argon as PasswordVerifier
    participant Authority as SessionTokenAuthority
    User->>API: POST /api/v1/auth/login
    API->>App: validated request + remote address
    App->>Redis: atomic INCR/EXPIRE(hashed email/address)
    alt Redis unavailable
        Redis-->>App: fail closed
        App->>DB: audit dependency-unavailable outcome
        App-->>API: 503 generic problem
    else attempt permitted
        App->>DB: load account and password credential
        App->>Argon: bounded Argon2id verification
        alt credential invalid or account disabled
            App->>DB: audit generic login failure
            App-->>API: 401 generic problem
        else credential valid
            App->>Authority: create session
            Authority->>DB: lock account, check cap, persist token digest
            App->>DB: audit success
            App-->>API: 200 session + short-lived access token
        end
    end
```

## Domain/class view

```mermaid
classDiagram
    class UserAccount {
        UUID id
        String email
        AccountStatus status
    }
    class PasswordCredential {
        UUID id
        String encodedPassword
        PasswordCredentialStatus status
    }
    class AuthSession {
        UUID id
        UUID accountId
        String accessTokenHash
        Instant expiresAt
        boolean revoked
    }
    class SecurityAuditEvent {
        SecurityAuditEventType eventType
        UUID accountId
        String correlationId
        String clientFingerprint
        Instant occurredAt
    }
    UserAccount "1" --> "0..1" PasswordCredential : owns
    UserAccount "1" --> "0..*" AuthSession : creates
    UserAccount "0..1" --> "0..*" SecurityAuditEvent : may identify
```

## State view

```mermaid
stateDiagram-v2
    [*] --> RequestReceived
    RequestReceived --> RateLimited : Redis counter increments
    RequestReceived --> DependencyUnavailable : Redis timeout/error
    RateLimited --> DependencyUnavailable : threshold exceeded
    RateLimited --> CredentialLookup : attempt permitted
    CredentialLookup --> GenericFailure : unknown/missing/disabled/wrong
    CredentialLookup --> HashCapacityWait : account + credential found
    HashCapacityWait --> DependencyUnavailable : permit timeout/interrupted
    HashCapacityWait --> GenericFailure : Argon2id mismatch
    HashCapacityWait --> SessionCapacityCheck : Argon2id match
    SessionCapacityCheck --> CapacityConflict : active session cap reached
    SessionCapacityCheck --> Authenticated : account lock + session persisted
    Authenticated --> AuditRecorded
    GenericFailure --> AuditRecorded
    RateLimited --> AuditRecorded
    DependencyUnavailable --> AuditRecorded
    CapacityConflict --> AuditRecorded
    AuditRecorded --> [*]
```

## Invariants

- Credential failures use one public error shape and do not disclose account existence.
- Raw passwords, bearer tokens, email addresses, and raw network addresses are not logged or
  persisted by the authentication path.
- Redis and local Argon2 capacity failures fail closed; no in-process rate-limit fallback exists.
- Session creation locks the account capacity row and stores only a SHA-256 access-token digest.
- Every security outcome is correlated and written to the redacted audit table when persistence is
  available.
