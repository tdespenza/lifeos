# Story 1.3 — OAuth2/OIDC login diagrams

These diagrams describe the implemented authorization-code flow at
`/api/v1/auth/oidc/{provider}/authorize` and
`/api/v1/auth/oidc/{provider}/callback`. The identity service is the provider allow-list,
callback, account-linking, audit, and LifeOS session authority. Redis stores only short-lived,
single-use callback state; PostgreSQL stores the verified external-identity mapping and durable
session metadata.

## Use-case view

```mermaid
flowchart LR
    User([User])
    Client["REST/browser client"]
    Provider([Supported OIDC provider])
    Begin(("Start authorization"))
    Redirect(("Complete provider authorization"))
    Callback(("Handle authorization callback"))
    Verify(("Validate state, PKCE, ID token, and account policy"))
    Session(("Create bounded LifeOS session"))
    Audit(("Record redacted security outcome"))
    Redis[("Redis callback state")]
    DB[("PostgreSQL identity store")]

    User --> Client --> Begin
    Begin --> Redis
    Begin --> Redirect
    Redirect <--> Provider
    Provider --> Callback
    Client --> Callback
    Callback --> Redis
    Callback --> Verify
    Verify --> DB
    Verify --> Session
    Session --> DB
    Verify --> Audit
    Session --> Audit
```

## Sequence view

```mermaid
sequenceDiagram
    actor User
    participant Client as REST/browser client
    participant API as OidcController
    participant App as OidcAuthenticationService
    participant Redis as RedisOidcStateStore
    participant Provider as OIDC provider
    participant DB as PostgreSQL
    participant Authority as SessionTokenAuthority

    User->>Client: Choose supported provider
    Client->>API: POST /authorize + S256 challenge + verifier
    API->>App: provider + challenge + verifier
    App->>Redis: SET state(provider, challenge, nonce, verifier) EX 5m
    alt Redis unavailable
        Redis-->>App: fail closed
        App-->>API: 503 generic problem
    else state stored
        App-->>Client: 302 configured provider authorization URI
        Client->>Provider: Authorize with state, nonce, and PKCE challenge
        Provider-->>API: Redirect code + state
        API->>App: callback parameters + client address
        Note over API,App: Browser path recovers verifier from single-use server state; client-held path uses the private callback header
        App->>Redis: atomic GET+DEL state
        alt state expired, reused, or mismatched
            Redis-->>App: empty or invalid state
            App->>DB: audit callback-rejected outcome
            App-->>API: 401 generic OIDC problem
        else state consumed and PKCE matches
            App->>Provider: Exchange code + verifier
            Provider-->>App: ID token and provider token response
            App->>App: Validate signature, issuer, audience, nonce, and email_verified
            alt provider claims invalid or email conflict
                App->>DB: audit callback-rejected outcome
                App-->>API: 401 generic OIDC problem
            else verified provider subject
                App->>DB: find or create external identity mapping
                App->>Authority: createSession(account, OIDC)
                Authority->>DB: lock account, check cap, persist token digest
                App->>DB: audit success in session transaction
                App-->>API: 200 shared session + short-lived access token
            end
        end
    end
```

## Domain/class view

```mermaid
classDiagram
    class OidcController {
        +authorize(provider, codeChallenge, method) ResponseEntity
        +callback(provider, code, state, verifier, error) ResponseEntity
    }
    class OidcAuthenticationService {
        +begin(provider, challenge) URI
        +callback(provider, code, state, verifier, error) LoginResponse
        -resolveAccount(provider, identity) UserAccount
        -verifyPkce(challenge, verifier) boolean
    }
    class OidcStateStore {
        <<interface>>
        +save(state, authorizationState, ttl)
        +consume(state) OidcAuthorizationState
    }
    class OidcAuthorizationState {
        String provider
        String redirectUri
        String codeChallenge
        String codeChallengeMethod
        String nonce
        String codeVerifier
    }
    class OidcProviderClient {
        <<interface>>
        +exchangeAndValidate(provider, code, verifier, nonce) OidcIdentity
    }
    class OidcIdentity {
        String subject
        String email
        String displayName
    }
    class UserAccount {
        UUID id
        String email
        AccountStatus status
    }
    class ExternalIdentity {
        UUID id
        String provider
        String subject
        UUID accountId
        Instant linkedAt
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

    OidcController --> OidcAuthenticationService : delegates
    OidcAuthenticationService --> OidcStateStore : consumes single-use state
    OidcAuthenticationService --> OidcProviderClient : exchanges and validates
    OidcAuthenticationService --> UserAccount : resolves or creates
    OidcAuthenticationService --> AuthSession : creates through authority
    OidcAuthenticationService --> SecurityAuditEvent : records outcome
    OidcStateStore --> OidcAuthorizationState : stores
    OidcProviderClient --> OidcIdentity : returns verified claims only
    UserAccount "1" --> "0..*" ExternalIdentity : owns verified mappings
    UserAccount "1" --> "0..*" AuthSession : creates
    UserAccount "0..1" --> "0..*" SecurityAuditEvent : may identify
```

## State view

```mermaid
stateDiagram-v2
    [*] --> AuthorizationRequested
    AuthorizationRequested --> ProviderNotConfigured : unknown provider
    AuthorizationRequested --> CallbackRejected : invalid PKCE challenge
    AuthorizationRequested --> StateStoreUnavailable : Redis write failure
    AuthorizationRequested --> StateStored : valid S256 challenge (+ verifier for browser path)
    StateStored --> ProviderAuthorization
    ProviderAuthorization --> CallbackReceived : provider redirects with code/state
    CallbackReceived --> CallbackRejected : provider error or missing input
    CallbackReceived --> StateStoreUnavailable : Redis read failure
    CallbackReceived --> CallbackRejected : expired or reused state
    CallbackReceived --> StateConsumed : state atomically consumed
    StateConsumed --> CallbackRejected : provider/redirect mismatch
    StateConsumed --> CallbackRejected : PKCE verifier mismatch
    StateConsumed --> ProviderExchange : PKCE verifier matches
    ProviderExchange --> ProviderExchangeUnavailable : provider exchange or JWKS dependency failure
    ProviderExchange --> CallbackRejected : provider token rejected or ID-token claims invalid
    ProviderExchange --> CallbackRejected : issuer/audience/signature/time invalid
    ProviderExchange --> CallbackRejected : nonce or verified-email invalid
    ProviderExchange --> AccountResolution : verified provider subject
    AccountResolution --> CallbackRejected : disabled account or email conflict
    AccountResolution --> DatabaseUnavailable : database dependency failure
    AccountResolution --> SessionCapacityConflict : active session cap reached
    AccountResolution --> SessionCreated : new or linked active account
    SessionCreated --> AuditRecorded
    CallbackRejected --> AuditRecorded
    SessionCapacityConflict --> AuditRecorded
    StateStoreUnavailable --> AuditRecorded
    ProviderExchangeUnavailable --> AuditRecorded
    DatabaseUnavailable --> AuditRecorded
    AuditRecorded --> [*]
```

## Invariants

- Only explicitly configured providers, issuer endpoints, client IDs, client credentials, JWKS
  URIs, and callback URIs are usable; provider transport is HTTPS, with loopback HTTP allowed only
  for local callbacks.
- Callback state is short-lived and atomically consumed. Expired, reused, provider-mismatched, or
  PKCE-mismatched callbacks cannot reach provider exchange, account linking, or session creation.
- Browser authorization starts retain the client-generated verifier only in the short-lived,
  single-use server state; the verifier is never appended to the provider redirect URI.
- ID tokens must pass signature and time validation plus exact issuer, configured audience, matching
  nonce, and `email_verified=true` checks. Provider access and refresh tokens are never persisted or
  returned to downstream LifeOS services.
- A new verified provider email may create a new LifeOS account. A matching email on an existing
  unlinked account is rejected; email equality alone never authorizes account takeover or linking.
- OIDC sessions use the shared session authority and durable session-capacity checks, without
  requiring a local password credential. Raw tokens, callback state, email addresses, and provider
  claims are not written to audit logs.
- Every callback rejection, dependency failure, capacity conflict, and successful OIDC session is
  recorded as a redacted security audit event with correlation and keyed client-fingerprint data
  when the audit store is available. If audit persistence itself fails, the service logs the
  operational failure and returns a generic temporary-failure response; no audit row can be
  claimed for that failure without a separate fallback sink.
- The browser POST flow intentionally retains the verifier in server-side state. This makes the
  provider redirect usable without a custom browser header, but an observer holding both callback
  code and state does not need an independently retained verifier; single-use state is the replay
  control. Clients requiring client-held PKCE proof must use the header-based callback path.
