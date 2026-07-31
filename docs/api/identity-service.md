# identity-service API

Base URL (local): `http://localhost:8081`

Status: registration only. No authentication (OAuth2/OIDC/JWT/passkeys) is implemented yet — see [ADR-001](../adr/ADR-001-use-java-25.md) and the identity-service section of [REQUIREMENTS.md](../../REQUIREMENTS.md#2-identity-service) for the target scope. `UserAccount` deliberately does not store credentials, to avoid half-implementing security-sensitive password handling ahead of a real auth design.

## `POST /api/v1/accounts`

Register a new account.

**Request body**

```json
{
  "email": "ada@example.com",
  "displayName": "Ada Lovelace"
}
```

`email` must be non-blank and a valid email address; `displayName` must be non-blank (enforced via Jakarta Bean Validation on [`RegisterAccountRequest`](../../services/identity-service/src/main/java/com/lifeos/identity/account/dto/RegisterAccountRequest.java)).

**Responses**

| Status | Condition | Body |
| --- | --- | --- |
| `201 Created` | Account created | `AccountResponse` (see below), `Location` header set to `/api/v1/accounts/{id}` |
| `400 Bad Request` | Validation failure (blank/invalid email or displayName) | Spring's default validation error body |
| `409 Conflict` | An account already exists for that email | Plain-text message |

**Example response (201)**

```json
{
  "id": "d49f7cc3-78d4-4d68-8abd-b76fb3d8a77d",
  "email": "ada@example.com",
  "displayName": "Ada Lovelace",
  "createdAt": "2026-07-31T04:10:02.468067Z"
}
```

## `GET /api/v1/accounts/{id}`

Fetch an account by id.

**Responses**

| Status | Condition | Body |
| --- | --- | --- |
| `200 OK` | Found | `AccountResponse` |
| `404 Not Found` | No account with that id | Plain-text message |

## `GET /actuator/health`

Spring Boot Actuator health check. Returns `{"status":"UP"}` when the service and its PostgreSQL connection are healthy. Only `health` and `info` actuator endpoints are exposed (see [`application.yml`](../../services/identity-service/src/main/resources/application.yml)) — no `/actuator/env`, `/actuator/beans`, etc. are exposed, to avoid leaking configuration details.

## Data store

PostgreSQL, database `lifeos_identity`, table `user_account` (id, email — unique, displayName, createdAt). See [`UserAccount`](../../services/identity-service/src/main/java/com/lifeos/identity/account/UserAccount.java). Schema is currently managed by Hibernate's `ddl-auto: update` — this is a known Phase 1 shortcut; a real migration tool (e.g. Flyway) should replace it before this goes further, per the persistence-model tradeoff called out in [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md).
