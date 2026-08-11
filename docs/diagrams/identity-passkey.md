# Story 1.4 — Passkey/WebAuthn login diagrams

These are UML views for the implemented passkey authentication flow in issue #132,
aligned with [ADR-020](../adr/ADR-020-use-identity-service-for-multi-mode-authentication-and-session-management.md).
They describe the implemented `POST /api/v1/auth/passkey/options` and
`POST /api/v1/auth/passkey/assertion` operations. The identity service owns challenge policy, WebAuthn assertion verification,
credential metadata, session creation, and security auditing. The authenticator retains the private
key and only returns a signed assertion.

## Use-case view

```mermaid
flowchart LR
    User([Registered user])
    Client["REST/browser client"]
    Authenticator([Platform authenticator or security key])
    Begin(("Begin passkey authentication"))
    Challenge(("Issue short-lived single-use challenge"))
    Assert(("Create WebAuthn assertion"))
    Verify(("Validate challenge, origin, RP ID, user verification, signature, and counter"))
    Session(("Create bounded LifeOS session"))
    Audit(("Record redacted security outcome"))
    Redis[("Redis challenge state")]
    DB[("PostgreSQL identity store")]

    User --> Client --> Begin
    Begin --> Challenge
    Challenge --> Redis
    Challenge --> Assert
    Assert <--> Authenticator
    Assert --> Client
    Client --> Verify
    Verify --> Redis
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
    participant API as PasskeyController
    participant App as PasskeyAuthenticationService
    participant Redis as WebAuthnChallengeStore
    participant Authenticator as Platform authenticator
    participant DB as PostgreSQL
    participant Verifier as WebAuthnAssertionVerifier
    participant Authority as SessionTokenAuthority

    User->>Client: Choose passkey sign-in
    Client->>API: Begin passkey authentication
    API->>App: authentication options request
    App->>Verifier: startAssertion(StartAssertionOptions)
    App->>Redis: Store random challenge + expected origin/RP ID (short TTL)
    alt Redis unavailable
        Redis-->>App: timeout or error
        App->>DB: Audit dependency-unavailable outcome
        App-->>API: 503 generic problem
    else challenge stored
        App-->>API: PublicKeyCredentialRequestOptions
        API-->>Client: Challenge options
        Client->>Authenticator: navigator.credentials.get(options)
        Authenticator-->>Client: Credential ID + signed assertion
        Client->>API: Complete passkey authentication + assertion
        API->>App: assertion request + correlation context
        App->>Redis: ValueOperations.getAndDelete(challenge key)
        alt Challenge stale, reused, or mismatched
            Redis-->>App: empty or invalid challenge
            App->>DB: Audit rejected outcome
            App-->>API: 401 generic authentication problem
        else Challenge consumed
            App->>Verifier: Load credential public key and stored counter by credential ID
            Verifier->>DB: WebAuthnCredentialRepositoryAdapter lookup
            alt Credential unknown or disabled
                DB-->>Verifier: no usable credential
                App->>DB: Audit rejected outcome
                App-->>API: 401 generic authentication problem
            else Credential available
                App->>Verifier: finishAssertion(FinishAssertionOptions)
                Verifier->>Verifier: Check origin, RP ID hash, user verification, and signature
                alt Origin/RP ID, user verification, or signature invalid
                    Verifier-->>App: Verification failure
                    App->>DB: Audit rejected outcome
                    App-->>API: 401 generic authentication problem
                else Assertion cryptographically valid
                    App->>DB: Atomically validate and advance authenticator counter
                    alt Counter regression or concurrent update conflict
                        DB-->>App: Counter check failed
                        App->>DB: Audit rejected outcome
                        App-->>API: 401 generic authentication problem
                    else Counter accepted
                        App->>Authority: createSession(account, PASSKEY)
                        Authority->>DB: Lock account, check capacity, persist session digest
                        App->>DB: Audit successful passkey authentication
                        App-->>API: 200 shared session + short-lived access token
                    end
                end
            end
        end
    end
```

## Domain/class view

```mermaid
classDiagram
    class PasskeyController {
        +options() PasskeyAuthenticationOptions
        +assertion(request) LoginResponse
    }
    class PasskeyAuthenticationService {
        +begin(clientAddress) PasskeyAuthenticationOptions
        +complete(request, clientAddress) LoginResponse
    }
    class WebAuthnChallengeStore {
        <<interface>>
        +save(challengeId, assertionRequest, ttl)
        +consume(challengeId) AssertionRequest
    }
    class AssertionRequest {
        Json requestJson
    }
    class RelyingParty {
        +startAssertion() AssertionRequest
        +finishAssertion(request, response) AssertionResult
    }
    class WebAuthnCredentialRepository {
        +findByCredentialIdAndEnabledTrue(id) WebAuthnCredential
        +advanceSignatureCountIfCurrent(id, expected, next) int
    }
    class AssertionResult {
        boolean success
        boolean userVerified
        long signatureCount
    }
    class WebAuthnAssertion {
        String credentialId
        String clientDataJson
        String authenticatorData
        String signature
        String userHandle
    }
    class WebAuthnCredential {
        UUID id
        String credentialId
        String publicKeyCose
        long signatureCount
        UUID accountId
        Instant createdAt
        Instant lastUsedAt
        boolean enabled
    }
    class UserAccount {
        UUID id
        String email
        AccountStatus status
    }
    class AuthSession {
        UUID id
        UUID accountId
        SessionAuthenticationMethod authenticationMethod
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
    class Authenticator {
        <<external>>
        PrivateKey privateKey
        +sign(challenge, clientData, authenticatorData) WebAuthnAssertion
    }

    PasskeyController --> PasskeyAuthenticationService : delegates
    PasskeyAuthenticationService --> WebAuthnChallengeStore : atomically consumes
    PasskeyAuthenticationService --> RelyingParty : validates
    PasskeyAuthenticationService --> WebAuthnCredentialRepository : loads and updates counter
    PasskeyAuthenticationService --> AuthSession : creates through authority
    PasskeyAuthenticationService --> SecurityAuditEvent : records outcome
    WebAuthnChallengeStore --> AssertionRequest : stores short-lived state
    RelyingParty --> AssertionResult : returns verified data
    RelyingParty --> WebAuthnCredential : uses public key metadata
    Authenticator ..> WebAuthnAssertion : signs with private key
    UserAccount "1" --> "0..*" WebAuthnCredential : owns
    UserAccount "1" --> "0..*" AuthSession : creates
    UserAccount "0..1" --> "0..*" SecurityAuditEvent : may identify
```

## State view

```mermaid
stateDiagram-v2
    [*] --> AuthenticationRequested
    AuthenticationRequested --> ChallengeStoreUnavailable : Redis write failure
    AuthenticationRequested --> ChallengeIssued : challenge stored with short TTL
    ChallengeIssued --> AuthenticatorInteraction
    AuthenticatorInteraction --> AuthenticationCancelled : user cancels or client fails
    AuthenticatorInteraction --> AssertionSubmitted : authenticator returns assertion
    AssertionSubmitted --> ChallengeStoreUnavailable : Redis read/consume failure
    AssertionSubmitted --> AuthenticationRejected : stale, reused, or mismatched challenge
    AssertionSubmitted --> CredentialLookup : challenge consumed once
    CredentialLookup --> AuthenticationRejected : unknown or disabled credential
    CredentialLookup --> AssertionVerification
    AssertionVerification --> AuthenticationRejected : wrong origin or RP ID
    AssertionVerification --> AuthenticationRejected : missing required user verification
    AssertionVerification --> AuthenticationRejected : invalid signature or assertion data
    AssertionVerification --> CounterConflict : counter regression or update race
    AssertionVerification --> CounterAccepted : assertion verified
    CounterAccepted --> SessionCapacityCheck
    SessionCapacityCheck --> SessionCapacityConflict : account/device cap reached
    SessionCapacityCheck --> SessionCreated : capacity check and session write succeed
    SessionCreated --> AuditRecorded
    AuthenticationRejected --> AuditRecorded
    CounterConflict --> AuditRecorded
    SessionCapacityConflict --> AuditRecorded
    ChallengeStoreUnavailable --> AuditRecorded
    AuthenticationCancelled --> [*]
    AuditRecorded --> [*]
```

## Invariants

- The private key never leaves the platform authenticator or security key. The identity service
  stores only the credential identifier, public key, account association, enabled status, and
  authenticator metadata needed for verification.
- Every challenge is cryptographically random, short-lived, bound to the configured origin and
  relying-party id, and consumed atomically once. A Redis timeout, stale challenge, or replay fails
  closed; no local or stale fallback can create a session.
- Assertion verification must bind the client data to the consumed challenge, require the expected
  origin and RP-ID hash, enforce the configured user-verification policy, and validate the signature
  against the registered credential public key before account resolution or session creation.
- Authenticator counters are checked and advanced atomically with the credential update. A counter
  regression or concurrent compare-and-set conflict is rejected and cannot issue a session.
- A valid assertion creates a session only through the shared ADR-020 session authority, including
  the `PASSKEY` authentication method, durable session-capacity checks, and the normal token/session
  response contract.
- Rejections, dependency failures, counter conflicts, capacity conflicts, and successful logins are
  recorded as redacted security-audit outcomes. Raw challenges, assertions, signatures, public-key
  material, tokens, cookies, and network addresses are not written to logs or audit payloads.
- Credential lookup is indexed by credential ID, challenge consumption is a bounded Redis operation,
  and counter advancement is one conditional database update. The authentication path is therefore
  O(1) in the number of registered credentials for normal requests; storage and network timeouts
  remain bounded by the service's authentication dependency budgets.
